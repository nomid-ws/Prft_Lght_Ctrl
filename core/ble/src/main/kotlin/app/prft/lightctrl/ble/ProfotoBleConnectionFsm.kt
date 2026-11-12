package app.prft.lightctrl.ble

import app.prft.lightctrl.protocol.PupDecodedMessage
import app.prft.lightctrl.protocol.PupFrameCodec
import app.prft.lightctrl.protocol.PupOpcodes
import app.prft.lightctrl.protocol.PupPacket
import app.prft.lightctrl.protocol.SequenceCounter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Deterministic 10-Stage Connection FSM + Dual Dispatch Pipeline for a single Profoto peripheral.
 *
 * Enforces:
 * - Strict initialization in [BleConnectionStage.Disconnected]
 * - 3000ms stage watchdog on MTU, discovery, auth handshake, and CCCD subscription
 * - Reflection-guarded GATT cache invalidation + 1000ms settle pause
 * - Out-of-band immediate priority channel for zero-latency TestFlash (Opcode 45)
 * - Mutex-gated FIFO command queue with 300ms/600ms ACK timeout & 3-attempt retry loop
 */
class ProfotoBleConnectionFsm(
    val device: DiscoveredProfotoDevice,
    private val link: BlePeerLink,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val stageTimeoutMs: Long = ProfotoGattProfile.STAGE_WATCHDOG_TIMEOUT_MS,
    private val cacheSettleMs: Long = ProfotoGattProfile.GATT_CACHE_SETTLE_MS,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val sequenceCounter = SequenceCounter(0)
    private val commandMutex = Mutex()

    private val _stage = MutableStateFlow<BleConnectionStage>(BleConnectionStage.Disconnected)
    val stage: StateFlow<BleConnectionStage> = _stage.asStateFlow()

    private val _incomingMessages = MutableSharedFlow<Pair<PupPacket, PupDecodedMessage>>(extraBufferCapacity = 128)
    val incomingMessages: SharedFlow<Pair<PupPacket, PupDecodedMessage>> = _incomingMessages.asSharedFlow()

    private val ackEvents = MutableSharedFlow<PupDecodedMessage>(extraBufferCapacity = 64)
    private val immediateFlashQueue = Channel<Unit>(Channel.CONFLATED)

    private var activeServiceUuid: UUID? = null
    private var negotiatedMtu: Int = 23
    @Volatile
    private var initialDumpCompleted: Boolean = false
    @Volatile
    private var hardwareIdReceived: Boolean = false
    private var eventCollectorJob: Job? = null
    private var flashDispatcherJob: Job? = null

    suspend fun connectAndAuthenticate(useLegacyV1HydrationFallback: Boolean = device.usesLegacyService): Boolean {
        disconnectQuietly()
        initialDumpCompleted = false
        hardwareIdReceived = false
        _stage.value = BleConnectionStage.Connecting

        startBackgroundPipelines()

        return try {
            // Stage 1-2: Link Connect
            withTimeout(stageTimeoutMs) {
                val connectedDeferred = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    link.events.filterIsInstance<RawGattEvent.Connected>().first()
                }
                link.connect()
                connectedDeferred.join()
            }
            _stage.value = BleConnectionStage.LinkConnected

            // Stage 3: Negotiate MTU 255 BEFORE service discovery
            val mtuEvent = withTimeout(stageTimeoutMs) {
                var captured: RawGattEvent.MtuChanged? = null
                val waiter = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    captured = link.events.filterIsInstance<RawGattEvent.MtuChanged>().first()
                }
                if (!link.requestMtu(ProfotoGattProfile.TARGET_MTU)) {
                    waiter.cancel()
                    throw IllegalStateException("requestMtu(255) rejected by BluetoothGatt")
                }
                waiter.join()
                captured ?: throw IllegalStateException("MTU event missing")
            }
            negotiatedMtu = mtuEvent.mtu
            _stage.value = BleConnectionStage.MtuNegotiated(negotiatedMtu)

            // Stage 4-5: Discover GATT Services; only flush stale cache if PUP TX/RX are missing
            var discovered = awaitServiceDiscovery()
            val hasPupEndpoints = ProfotoGattProfile.PUP_TX_CHAR_UUID in discovered.characteristicUuids &&
                ProfotoGattProfile.PUP_RX_CHAR_UUID in discovered.characteristicUuids
            if (!hasPupEndpoints) {
                val refreshed = link.refreshGattCache()
                if (cacheSettleMs > 0) {
                    delay(cacheSettleMs)
                }
                _stage.value = BleConnectionStage.CacheFlushed(refreshed)
                discovered = awaitServiceDiscovery()
            }

            if (ProfotoGattProfile.PUP_TX_CHAR_UUID !in discovered.characteristicUuids ||
                ProfotoGattProfile.PUP_RX_CHAR_UUID !in discovered.characteristicUuids
            ) {
                throw IllegalStateException("Missing PUP TX/RX characteristics on ${device.address}")
            }
            val resolvedService = when {
                ProfotoGattProfile.PRIMARY_SIG_SERVICE_UUID in discovered.serviceUuids ->
                    ProfotoGattProfile.PRIMARY_SIG_SERVICE_UUID
                ProfotoGattProfile.LEGACY_BASE_SERVICE_UUID in discovered.serviceUuids ->
                    ProfotoGattProfile.LEGACY_BASE_SERVICE_UUID
                ProfotoGattProfile.AUTH_SERVICE_UUID in discovered.serviceUuids ->
                    ProfotoGattProfile.AUTH_SERVICE_UUID
                else -> discovered.serviceUuids.firstOrNull { it !in ProfotoGattProfile.FORBIDDEN_DFU_SERVICE_UUIDS }
                    ?: throw IllegalStateException("No Profoto GATT service found on ${device.address}")
            }
            activeServiceUuid = resolvedService
            _stage.value = BleConnectionStage.ServicesDiscovered(resolvedService)

            // Stage 6: Enable Notifications (CCCD 0x2902) on PUP RX (049e2ea9-...) FIRST
            // (Matches official Profoto Control N7/a.java -> S2/m.java so auto-streamed AllSettingsV2 is captured)
            ProfotoGattProfile.assertSafeCharacteristicUuid(ProfotoGattProfile.PUP_RX_CHAR_UUID)
            withTimeout(stageTimeoutMs) {
                var cccdEvt: RawGattEvent.CccdWritten? = null
                val waiter = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    cccdEvt = link.events.filterIsInstance<RawGattEvent.CccdWritten>()
                        .first { it.characteristicUuid == ProfotoGattProfile.PUP_RX_CHAR_UUID }
                }
                val enabled = link.enableNotifications(
                    resolvedService,
                    ProfotoGattProfile.PUP_RX_CHAR_UUID,
                    ProfotoGattProfile.CCCD_UUID,
                )
                if (!enabled) {
                    waiter.cancel()
                    throw IllegalStateException("CCCD notification subscription rejected")
                }
                waiter.join()
                if (cccdEvt?.status != 0) {
                    throw IllegalStateException("CCCD write failed with status ${cccdEvt?.status}")
                }
            }
            _stage.value = BleConnectionStage.NotificationsSubscribed

            // Stage 7: Write UTF-8 "Profoto Client" handshake to c655ad5a-... (lives under bbd75314-... on real hardware;
            // skipped gracefully if characteristic is absent on legacy Profoto Connect per W2/a.java:51-58)
            if (ProfotoGattProfile.AUTH_CLIENT_ID_CHAR_UUID in discovered.characteristicUuids) {
                ProfotoGattProfile.assertSafeCharacteristicUuid(ProfotoGattProfile.AUTH_CLIENT_ID_CHAR_UUID)
                withTimeout(stageTimeoutMs) {
                    var writeEvt: RawGattEvent.CharacteristicWritten? = null
                    val waiter = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        writeEvt = link.events.filterIsInstance<RawGattEvent.CharacteristicWritten>()
                            .first { it.uuid == ProfotoGattProfile.AUTH_CLIENT_ID_CHAR_UUID }
                    }
                    val initiated = link.writeCharacteristicWithResponse(
                        ProfotoGattProfile.AUTH_SERVICE_UUID,
                        ProfotoGattProfile.AUTH_CLIENT_ID_CHAR_UUID,
                        ProfotoGattProfile.HANDSHAKE_TOKEN_BYTES,
                    )
                    if (!initiated) {
                        waiter.cancel()
                        throw IllegalStateException("Handshake write initiation failed")
                    }
                    waiter.join()
                    if (writeEvt?.status != 0) {
                        throw IllegalStateException("Handshake rejected with GATT status ${writeEvt?.status}")
                    }
                }
            }
            _stage.value = BleConnectionStage.HandshakeAuthenticated

            // Stage 8-9: Hydrate GetAllSettings (Opcode 27) + HardwareId + AllSettingsV2 (+ Legacy V1 fallback)
            _stage.value = BleConnectionStage.HydratingSettings
            hydrateInitialState(resolvedService, useLegacyV1HydrationFallback)

            // Stage 10: Ready for live studio control
            _stage.value = BleConnectionStage.Ready(negotiatedMtu, resolvedService)
            true
        } catch (t: Throwable) {
            val failedAt = _stage.value::class.simpleName ?: "Connect"
            disconnectQuietly()
            _stage.value = BleConnectionStage.Failed(failedAt, t.message ?: t::class.java.simpleName)
            false
        }
    }

    private suspend fun awaitServiceDiscovery(): RawGattEvent.ServicesDiscovered =
        withTimeout(stageTimeoutMs) {
            var captured: RawGattEvent.ServicesDiscovered? = null
            val waiter = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                captured = link.events.filterIsInstance<RawGattEvent.ServicesDiscovered>().first()
            }
            if (!link.discoverServices()) {
                waiter.cancel()
                throw IllegalStateException("discoverServices() rejected")
            }
            waiter.join()
            captured ?: throw IllegalStateException("ServicesDiscovered event missing")
        }

    /**
     * Out-of-band immediate priority trigger for zero-latency TestFlash (Opcode 45).
     * Never blocked behind slider scrubbing packets.
     */
    fun triggerImmediateTestFlash() {
        immediateFlashQueue.trySend(Unit)
    }

    /**
     * Send an operational PUP command through the mutex-gated FIFO queue with ACK retry.
     */
    suspend fun sendCommand(
        opcode: Int,
        payloadBuilder: (counter: Int) -> ByteArray,
    ): Boolean {
        val serviceUuid = activeServiceUuid ?: return false
        if (_stage.value !is BleConnectionStage.Ready && _stage.value !is BleConnectionStage.HydratingSettings) {
            return false
        }
        ProfotoGattProfile.assertSafeCharacteristicUuid(ProfotoGattProfile.PUP_TX_CHAR_UUID)

        val expectAck = opcode !in PupOpcodes.NON_ACKABLE_OPCODES &&
            (opcode and 0x8000) == 0

        if (!expectAck) {
            val seq = sequenceCounter.next()
            val frame = payloadBuilder(seq)
            logPup("TX[${device.address}] (no-ack) seq=$seq op=$opcode hex=${frame.toHex()}")
            return link.writeCharacteristicNoResponse(serviceUuid, ProfotoGattProfile.PUP_TX_CHAR_UUID, frame)
        }

        val timeoutMs = if (opcode == PupOpcodes.ADJUST_AIR_MX_ENERGY_IF_ABLE) {
            ProfotoGattProfile.EXTENDED_ACK_TIMEOUT_MS
        } else {
            ProfotoGattProfile.STANDARD_ACK_TIMEOUT_MS
        }

        return commandMutex.withLock {
            repeat(ProfotoGattProfile.MAX_COMMAND_RETRIES) { attempt ->
                val seq = sequenceCounter.next()
                val frame = payloadBuilder(seq)
                logPup("TX[${device.address}] attempt=${attempt + 1} seq=$seq op=$opcode hex=${frame.toHex()}")

                var matchedAck: Boolean? = null
                val ackWaiter = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    val response = ackEvents.first { msg ->
                        (msg is PupDecodedMessage.Ack && msg.acknowledgedCounter == seq) ||
                            (msg is PupDecodedMessage.Nack && msg.rejectedCounter == seq)
                    }
                    matchedAck = response is PupDecodedMessage.Ack
                }

                val written = link.writeCharacteristicNoResponse(
                    serviceUuid,
                    ProfotoGattProfile.PUP_TX_CHAR_UUID,
                    frame,
                )
                if (!written) {
                    ackWaiter.cancel()
                    logPup("TX[${device.address}] write failed seq=$seq op=$opcode")
                    return@withLock false
                }

                try {
                    withTimeout(timeoutMs) {
                        ackWaiter.join()
                    }
                    if (matchedAck == true) {
                        return@withLock true
                    }
                    if (matchedAck == false) {
                        logPup("RX[${device.address}] explicit NACK for seq=$seq op=$opcode")
                        return@withLock false
                    }
                } catch (_: Throwable) {
                    ackWaiter.cancel()
                    logPup("TX[${device.address}] ACK timeout (${timeoutMs}ms) seq=$seq op=$opcode")
                }
            }
            false
        }
    }

    suspend fun sendQuery(opcode: Int): Boolean {
        val serviceUuid = activeServiceUuid ?: return false
        val frame = PupFrameCodec.encodeQuery(sequenceCounter.next(), opcode)
        return link.writeCharacteristicNoResponse(serviceUuid, ProfotoGattProfile.PUP_TX_CHAR_UUID, frame)
    }

    suspend fun disconnect() {
        disconnectQuietly()
        _stage.value = BleConnectionStage.Disconnected
    }

    private suspend fun hydrateInitialState(serviceUuid: UUID, includeLegacyV1Fallback: Boolean) {
        if (initialDumpCompleted && (hardwareIdReceived || device.advertisedProductId != 0)) return

        val hydrationWaiter = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            _incomingMessages.first { (_, decoded) ->
                (decoded is PupDecodedMessage.BulkSettingsDump && decoded.result.finished) ||
                    (includeLegacyV1Fallback && decoded is PupDecodedMessage.HardwareProductId)
            }
        }

        // 1. Official Profoto Control GetAllSettings (Opcode 27, L7/C0647z3.java) — triggers full AllSettingsV2(fin=true)
        // on both Profoto Connect (151) and AirX lights (A2, B10X, Connect Pro) in <150ms without triggering NACKs.
        val getAllV1 = PupFrameCodec.encodeGetAllSettingsV1(sequenceCounter.next())
        logPup("TX[${device.address}] (hydrate) GetAllSettings op=27 hex=${getAllV1.toHex()}")
        link.writeCharacteristicNoResponse(serviceUuid, ProfotoGattProfile.PUP_TX_CHAR_UUID, getAllV1)

        if (!initialDumpCompleted) {
            withTimeoutOrNull(650L) {
                hydrationWaiter.join()
            }
        }

        // 2. Fallback queries ONLY if Opcode 27 did not finish hydrating within 650ms
        if (!initialDumpCompleted) {
            val dumpQuery = PupFrameCodec.encodeQueryAllSettingsV2(sequenceCounter.next())
            link.writeCharacteristicNoResponse(serviceUuid, ProfotoGattProfile.PUP_TX_CHAR_UUID, dumpQuery)

            if (includeLegacyV1Fallback) {
                val v1Hw = PupFrameCodec.encodeLegacyV1Query(sequenceCounter.next(), PupOpcodes.QUERY_HARDWARE_ID)
                val v1Bat = PupFrameCodec.encodeLegacyV1Query(sequenceCounter.next(), PupOpcodes.QUERY_BATTERY_INDICATOR)
                val v1ChRange = PupFrameCodec.encodeLegacyV1Query(sequenceCounter.next(), PupOpcodes.QUERY_AIR_CHANNEL_RANGE)
                link.writeCharacteristicNoResponse(serviceUuid, ProfotoGattProfile.PUP_TX_CHAR_UUID, v1Hw)
                link.writeCharacteristicNoResponse(serviceUuid, ProfotoGattProfile.PUP_TX_CHAR_UUID, v1Bat)
                link.writeCharacteristicNoResponse(serviceUuid, ProfotoGattProfile.PUP_TX_CHAR_UUID, v1ChRange)
            }

            withTimeoutOrNull(650L) {
                hydrationWaiter.join()
            }
        }

        if (!hardwareIdReceived && device.advertisedProductId == 0) {
            val hwQuery = PupFrameCodec.encodeQuery(sequenceCounter.next(), PupOpcodes.HARDWARE_ID)
            link.writeCharacteristicNoResponse(serviceUuid, ProfotoGattProfile.PUP_TX_CHAR_UUID, hwQuery)
        }
        hydrationWaiter.cancel()
    }

    private fun startBackgroundPipelines() {
        eventCollectorJob?.cancel()
        eventCollectorJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            link.events.collect { evt ->
                when (evt) {
                    is RawGattEvent.Disconnected -> {
                        if (_stage.value is BleConnectionStage.Ready) {
                            _stage.value = BleConnectionStage.Disconnected
                        }
                    }
                    is RawGattEvent.CharacteristicNotified -> {
                        if (evt.uuid == ProfotoGattProfile.PUP_RX_CHAR_UUID) {
                            handleIncomingNotification(evt.payload)
                        }
                    }
                    else -> Unit
                }
            }
        }

        flashDispatcherJob?.cancel()
        flashDispatcherJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            for ( ignored in immediateFlashQueue) {
                val serviceUuid = activeServiceUuid ?: continue
                if (_stage.value is BleConnectionStage.Ready) {
                    val frame = PupFrameCodec.encodeTestFlash(sequenceCounter.next())
                    link.writeCharacteristicNoResponse(
                        serviceUuid,
                        ProfotoGattProfile.PUP_TX_CHAR_UUID,
                        frame,
                    )
                }
            }
        }
    }

    private suspend fun handleIncomingNotification(raw: ByteArray) {
        val (pkt, decoded) = try {
            PupFrameCodec.decodeMessage(raw)
        } catch (t: Exception) {
            logPup("RX[${device.address}] decode error (${t.message}): hex=${raw.toHex()}")
            return
        }

        if (decoded is PupDecodedMessage.BulkSettingsDump) {
            val entriesLog = decoded.result.entries.joinToString(", ") { entry ->
                val dec = entry.decoded
                if (dec == null || dec is PupDecodedMessage.RawUnknown) {
                    "op=${entry.opcode}(raw=${entry.payload.toHex()})"
                } else {
                    "op=${entry.opcode}($dec)"
                }
            }
            logPup("RX[${device.address}] seq=${pkt.counter} AllSettingsV2(fin=${decoded.result.finished}): [$entriesLog]")
        } else {
            val detail = if (decoded is PupDecodedMessage.RawUnknown) {
                "RawUnknown(op=${decoded.opcode}, hex=${decoded.bytes.toHex()})"
            } else {
                decoded.toString()
            }
            logPup("RX[${device.address}] seq=${pkt.counter} op=${pkt.opcode} q=${pkt.isQuery} -> $detail")
        }

        if (decoded is PupDecodedMessage.Ack || decoded is PupDecodedMessage.Nack) {
            ackEvents.tryEmit(decoded)
        } else if (!pkt.isQuery && pkt.opcode !in PupOpcodes.NON_ACKABLE_OPCODES) {
            // Acknowledge peripheral-originated ackable state changes asynchronously
            activeServiceUuid?.let { svc ->
                val ackBytes = PupFrameCodec.encodeAck(sequenceCounter.next(), pkt.counter)
                scope.launch {
                    link.writeCharacteristicNoResponse(svc, ProfotoGattProfile.PUP_TX_CHAR_UUID, ackBytes)
                }
            }
        }
        _incomingMessages.emit(pkt to decoded)

        if (decoded is PupDecodedMessage.BulkSettingsDump) {
            if (decoded.result.entries.any { it.decoded is PupDecodedMessage.HardwareProductId }) {
                hardwareIdReceived = true
            }
            if (decoded.result.finished) {
                initialDumpCompleted = true
            }
        } else if (decoded is PupDecodedMessage.HardwareProductId) {
            hardwareIdReceived = true
        }
    }

    private fun logPup(message: String) {
        runCatching { android.util.Log.d("ProfotoPup", message) }
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private suspend fun disconnectQuietly() {
        eventCollectorJob?.cancel()
        eventCollectorJob = null
        flashDispatcherJob?.cancel()
        flashDispatcherJob = null
        activeServiceUuid = null
        try {
            link.close()
        } catch (_: Throwable) {
        }
    }
}
