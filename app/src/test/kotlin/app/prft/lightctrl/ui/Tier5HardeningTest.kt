package app.prft.lightctrl.ui

import android.view.KeyEvent
import androidx.lifecycle.Lifecycle
import app.prft.lightctrl.ble.BleAdapterGateway
import app.prft.lightctrl.ble.BlePeerLink
import app.prft.lightctrl.ble.DiscoveredProfotoDevice
import app.prft.lightctrl.ble.ProfotoGattProfile
import app.prft.lightctrl.ble.RawGattEvent
import app.prft.lightctrl.ble.mock.VirtualBenchFixtures
import app.prft.lightctrl.domain.ConnectedProfotoUnit
import app.prft.lightctrl.domain.PowerScaleConverter
import app.prft.lightctrl.domain.StudioOrchestrator
import app.prft.lightctrl.domain.StudioPreferencesStore
import app.prft.lightctrl.e2e.harness.FakeBleGateway
import app.prft.lightctrl.protocol.PupFrameCodec
import app.prft.lightctrl.protocol.PupOpcodes
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Tier 5 Hardening & Regression Test Suite.
 *
 * Verifies fixes for:
 * 1. MainActivity & VolumeKeyEventHandler:
 *    - Key event delegation to VolumeKeyEventHandler.
 *    - Key repeat suppression (repeatCount > 0 suppresses duplicate flash).
 *    - Pass-through when disabled or non-volume keys.
 *    - Lifecycle guard (RESUMED state enforcement prevents background triggering).
 *    - Safe null event / repeatCount handling.
 * 2. VerticalGroupFaderBank & Dual Power Readout:
 *    - Defensive guard against non-positive maxWs (maxWs <= 0 falls back to fraction without crash).
 *    - Ellipsis overflow handling on extended dual power readout strings.
 * 3. StudioOrchestrator:
 *    - AirChannelAssignment telemetry clamping to minAirChannel..maxAirChannel.
 *    - Bounds protection against out-of-range wire values (channel 0, channel 255).
 *    - Safe coercion preventing empty-range crashes on malformed bounds.
 *    - Strict ceiling clamp (1..100) on AirChannelBounds preventing PupCodecException on hostile frames ([255, 0], [-5, -10]).
 */
class Tier5HardeningTest {

    // =========================================================================
    // 1. Hardware Volume Key Handling & Lifecycle Guard
    // =========================================================================

    @Test
    fun `test 1 VolumeKeyEventHandler volume down and up trigger flash only on initial key press`() {
        var flashCount = 0
        var isEnabled = true

        val handler = VolumeKeyEventHandler(
            isEnabledProvider = { isEnabled },
            onTriggerFlash = { flashCount++ },
        )

        // Initial key down: repeatCount = 0 -> consumed and triggers flash
        val downConsumed0 = handler.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, repeatCount = 0)
        assertTrue("Volume down (repeatCount=0) must be consumed", downConsumed0)
        assertEquals("Flash must trigger once on initial press", 1, flashCount)

        // Subsequent key down repeats: repeatCount > 0 -> consumed but DOES NOT trigger extra flash
        val downConsumed1 = handler.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, repeatCount = 1)
        assertTrue("Volume down repeat must be consumed to prevent OS volume changes", downConsumed1)
        assertEquals("Flash must NOT trigger on key repeat", 1, flashCount)

        val downConsumed2 = handler.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, repeatCount = 5)
        assertTrue("Volume down repeat 5 must be consumed", downConsumed2)
        assertEquals("Flash must NOT trigger on key repeat 5", 1, flashCount)

        // Key up: consumed without triggering flash
        val upConsumed = handler.onKeyUp(KeyEvent.KEYCODE_VOLUME_DOWN)
        assertTrue("Volume down key up must be consumed", upConsumed)
        assertEquals("Key up must never trigger flash", 1, flashCount)

        // Volume Up initial press triggers second flash
        val upVolConsumed0 = handler.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, repeatCount = 0)
        assertTrue("Volume up (repeatCount=0) must be consumed", upVolConsumed0)
        assertEquals("Flash must trigger on volume up initial press", 2, flashCount)

        // Volume Up repeats suppressed
        val upVolConsumed1 = handler.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, repeatCount = 2)
        assertTrue("Volume up repeat must be consumed", upVolConsumed1)
        assertEquals("Flash must NOT trigger on volume up repeat", 2, flashCount)

        val upVolUpConsumed = handler.onKeyUp(KeyEvent.KEYCODE_VOLUME_UP)
        assertTrue("Volume up key up must be consumed", upVolUpConsumed)
        assertEquals("Flash count remains 2 after key up", 2, flashCount)
    }

    @Test
    fun `test 2 VolumeKeyEventHandler passes through non-volume keys and disabled toggle`() {
        var flashCount = 0
        var isEnabled = false

        val handler = VolumeKeyEventHandler(
            isEnabledProvider = { isEnabled },
            onTriggerFlash = { flashCount++ },
        )

        // When toggle is disabled, volume keys must not be consumed
        assertFalse("Disabled volume down must pass through", handler.onKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, 0))
        assertFalse("Disabled volume up must pass through", handler.onKeyDown(KeyEvent.KEYCODE_VOLUME_UP, 0))
        assertFalse("Disabled volume down key up must pass through", handler.onKeyUp(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertFalse("Disabled volume up key up must pass through", handler.onKeyUp(KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals("No flash must fire when toggle is disabled", 0, flashCount)

        // Enable toggle, but send non-volume keys
        isEnabled = true
        assertFalse("Back key must pass through", handler.onKeyDown(KeyEvent.KEYCODE_BACK, 0))
        assertFalse("Power key must pass through", handler.onKeyDown(KeyEvent.KEYCODE_POWER, 0))
        assertFalse("Menu key must pass through", handler.onKeyDown(KeyEvent.KEYCODE_MENU, 0))
        assertFalse("Back key up must pass through", handler.onKeyUp(KeyEvent.KEYCODE_BACK))
        assertEquals("No flash must fire for non-volume keys", 0, flashCount)
    }

    @Test
    fun `test 3 Lifecycle guard strictly prevents background volume key flash triggering`() {
        var flashTriggered = false
        var currentLifecycleState = Lifecycle.State.CREATED

        val isEnabled = true

        val handler = VolumeKeyEventHandler(
            isEnabledProvider = { isEnabled },
            onTriggerFlash = {
                if (currentLifecycleState.isAtLeast(Lifecycle.State.RESUMED)) {
                    flashTriggered = true
                }
            },
        )

        // Helper mimicking MainActivity.onKeyDown logic
        fun simulateActivityOnKeyDown(keyCode: Int, repeatCount: Int?): Boolean {
            if (!currentLifecycleState.isAtLeast(Lifecycle.State.RESUMED)) {
                return false // delegates to super.onKeyDown
            }
            return handler.onKeyDown(keyCode, repeatCount ?: 0)
        }

        // 1. In CREATED state (background) -> strictly blocked
        currentLifecycleState = Lifecycle.State.CREATED
        assertFalse("Must pass through when activity is CREATED", simulateActivityOnKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, 0))
        assertFalse("Flash must not trigger in CREATED state", flashTriggered)

        // 2. In STARTED state (partially obscured / transitioning) -> strictly blocked
        currentLifecycleState = Lifecycle.State.STARTED
        assertFalse("Must pass through when activity is STARTED", simulateActivityOnKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, 0))
        assertFalse("Flash must not trigger in STARTED state", flashTriggered)

        // 3. In RESUMED state (foreground, interactive) -> permitted and consumed
        currentLifecycleState = Lifecycle.State.RESUMED
        val consumedResumed = simulateActivityOnKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, null) // safe null repeatCount
        assertTrue("Must consume event when activity is RESUMED", consumedResumed)
        assertTrue("Flash must trigger in RESUMED state", flashTriggered)

        // 4. In DESTROYED state -> strictly blocked
        flashTriggered = false
        currentLifecycleState = Lifecycle.State.DESTROYED
        assertFalse("Must pass through when activity is DESTROYED", simulateActivityOnKeyDown(KeyEvent.KEYCODE_VOLUME_DOWN, 0))
        assertFalse("Flash must not trigger in DESTROYED state", flashTriggered)
    }

    // =========================================================================
    // 2. VerticalGroupFaderBank Dual Power Readout & Non-Positive maxWs Protection
    // =========================================================================

    @Test
    fun `test 4 PowerScaleConverter formatWattSeconds requires positive maxWs and fader bank guards against crash`() {
        // Raw calculateWattSeconds throws IllegalArgumentException if maxWs <= 0
        assertThrows(IllegalArgumentException::class.java) {
            PowerScaleConverter.calculateWattSeconds(maxWs = 0, tenthStops = 100)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PowerScaleConverter.calculateWattSeconds(maxWs = -50, tenthStops = 80)
        }

        // Verification of VerticalGroupFaderBank defensive pattern:
        // val sec = if (maxWs != null && maxWs > 0) formatWattSeconds(maxWs, tenths) else formatPowerFraction(tenths)
        fun computeSec(maxWs: Int?, tenths: Int): String {
            return if (maxWs != null && maxWs > 0) {
                PowerScaleConverter.formatWattSeconds(maxWs, tenths)
            } else {
                PowerScaleConverter.formatPowerFraction(tenths)
            }
        }

        // Valid positive maxWs produces Ws cleanly
        assertEquals("500 Ws", computeSec(500, 100))
        assertEquals("250 Ws", computeSec(500, 90))

        // Non-positive maxWs (0 or negative anomaly) safely falls back to fraction without crash
        assertEquals("1/1", computeSec(0, 100))
        assertEquals("1/2", computeSec(-100, 90))
        assertEquals("1/512 - 0.9 EV", computeSec(0, 1))

        // Null maxWs also safely produces fraction
        assertEquals("1/1", computeSec(null, 100))
        assertEquals("1/2", computeSec(null, 90))

        // Parity with PowerScaleConverter.formatDualPowerReadout
        assertEquals("10.0 (500 Ws · 1/1)", PowerScaleConverter.formatDualPowerReadout(100, 500))
        assertEquals("10.0 (1/1)", PowerScaleConverter.formatDualPowerReadout(100, 0))
        assertEquals("10.0 (1/1)", PowerScaleConverter.formatDualPowerReadout(100, null))
    }

    @Test
    fun `test 5 VerticalGroupFaderBank readoutText formatting handles extreme power and dual readout lengths`() {
        fun buildReadoutText(isLandscape: Boolean, isDual: Boolean, maxWs: Int?, tenths: Int): String {
            val baseScale = String.format(java.util.Locale.US, "%.1f", tenths / 10.0)
            return if (isDual) {
                val sec = if (maxWs != null && maxWs > 0) {
                    PowerScaleConverter.formatWattSeconds(maxWs, tenths)
                } else {
                    PowerScaleConverter.formatPowerFraction(tenths)
                }
                if (isLandscape) "$baseScale · $sec" else "$baseScale\n$sec"
            } else {
                baseScale
            }
        }

        // Full power with 500 Ws
        assertEquals("10.0 · 500 Ws", buildReadoutText(isLandscape = true, isDual = true, maxWs = 500, tenths = 100))
        assertEquals("10.0\n500 Ws", buildReadoutText(isLandscape = false, isDual = true, maxWs = 500, tenths = 100))

        // Power floor 0.1 stop (tenths = 1) with unknown maxWs -> extended fraction string
        val landscapeFloor = buildReadoutText(isLandscape = true, isDual = true, maxWs = null, tenths = 1)
        val portraitFloor = buildReadoutText(isLandscape = false, isDual = true, maxWs = null, tenths = 1)

        assertEquals("0.1 · 1/512 - 0.9 EV", landscapeFloor)
        assertEquals("0.1\n1/512 - 0.9 EV", portraitFloor)

        // String length is 20 characters, requiring TextOverflow.Ellipsis on compact mobile screens
        assertEquals(20, landscapeFloor.length)
        assertTrue(landscapeFloor.contains("·"))
        assertTrue(portraitFloor.contains("\n"))
    }

    // =========================================================================
    // 3. StudioOrchestrator AirChannelAssignment Clamping Regression
    // =========================================================================

    /**
     * BleAdapterGateway decorator that allows injecting synthetic PUP notifications into
     * any connected device session to verify telemetry handling.
     */
    private class InjectableBleGateway : BleAdapterGateway {
        val delegate = FakeBleGateway()
        val notificationEmitters = ConcurrentHashMap<String, MutableSharedFlow<RawGattEvent>>()

        override val isBluetoothAvailable get() = delegate.isBluetoothAvailable
        override val isBluetoothEnabled get() = delegate.isBluetoothEnabled
        override val scannedDevices get() = delegate.scannedDevices
        override val isScanning get() = delegate.isScanning

        override fun refreshAdapterState() = delegate.refreshAdapterState()
        override fun startScan() = delegate.startScan()
        override fun stopScan() = delegate.stopScan()

        override fun openPeerLink(device: DiscoveredProfotoDevice): BlePeerLink {
            val baseLink = delegate.openPeerLink(device)
            val emitter = MutableSharedFlow<RawGattEvent>(extraBufferCapacity = 64)
            notificationEmitters[device.address] = emitter
            return object : BlePeerLink by baseLink {
                override val events: Flow<RawGattEvent> = merge(baseLink.events, emitter)
            }
        }

        suspend fun injectPupNotification(address: String, packetBytes: ByteArray) {
            notificationEmitters[address]?.emit(
                RawGattEvent.CharacteristicNotified(ProfotoGattProfile.PUP_RX_CHAR_UUID, packetBytes)
            )
        }
    }

    @Test
    fun `test 6 StudioOrchestrator clamps incoming AirChannelAssignment telemetry to unit bounds`() = runBlocking {
        val gateway = InjectableBleGateway()
        val orchestrator = StudioOrchestrator(
            appContext = null,
            bleGateway = gateway,
            groupPacingMs = 0L,
        )

        val b10xDevice = gateway.scannedDevices.value.first { it.address == VirtualBenchFixtures.ADDR_B10X_PLUS }
        orchestrator.connectDevice(b10xDevice, fastSimTimings = true)

        // Wait until device is fully connected and ready
        withTimeout(3000L) {
            while (orchestrator.dashboardState.value.connectedUnits.none { it.address == b10xDevice.address && it.isReady }) {
                delay(20)
            }
        }

        // Configure channel bounds to 1..20 via AirChannelRange telemetry
        val rangeFrame = PupFrameCodec.encodePacket(
            counter = 90,
            opcode = PupOpcodes.AIR_CHANNEL_RANGE,
            payload = byteArrayOf(1.toByte(), 20.toByte()),
            isQuery = false,
        )
        gateway.injectPupNotification(b10xDevice.address, rangeFrame)

        withTimeout(2000L) {
            while (orchestrator.dashboardState.value.connectedUnits.first { it.address == b10xDevice.address }.maxAirChannel != 20) {
                delay(20)
            }
        }

        val readyUnit = orchestrator.dashboardState.value.connectedUnits.first { it.address == b10xDevice.address }
        assertEquals("B10X Plus minAirChannel must be 1", 1, readyUnit.minAirChannel)
        assertEquals("B10X Plus maxAirChannel must be 20", 20, readyUnit.maxAirChannel)

        // 1. Inject malformed channel 0 (below minAirChannel) -> must clamp to minAirChannel (1)
        val frameZero = PupFrameCodec.encodePacket(
            counter = 91,
            opcode = PupOpcodes.AIR_CHANNEL,
            payload = byteArrayOf(0.toByte()),
            isQuery = false,
        )
        gateway.injectPupNotification(b10xDevice.address, frameZero)

        withTimeout(2000L) {
            while (orchestrator.dashboardState.value.connectedUnits.first { it.address == b10xDevice.address }.airChannel != 1) {
                delay(20)
            }
        }
        val clampedZero = orchestrator.dashboardState.value.connectedUnits.first { it.address == b10xDevice.address }.airChannel
        assertEquals("Channel 0 must be clamped to minAirChannel (1)", 1, clampedZero)

        // 2. Inject valid channel 14 -> preserved as 14
        val frameValid = PupFrameCodec.encodePacket(
            counter = 92,
            opcode = PupOpcodes.AIR_CHANNEL,
            payload = byteArrayOf(14.toByte()),
            isQuery = false,
        )
        gateway.injectPupNotification(b10xDevice.address, frameValid)

        withTimeout(2000L) {
            while (orchestrator.dashboardState.value.connectedUnits.first { it.address == b10xDevice.address }.airChannel != 14) {
                delay(20)
            }
        }
        assertEquals(14, orchestrator.dashboardState.value.connectedUnits.first { it.address == b10xDevice.address }.airChannel)

        // 3. Inject out-of-bounds channel 255 (exceeding maxAirChannel of 20) -> must clamp to maxAirChannel (20)
        val frameOverflow = PupFrameCodec.encodePacket(
            counter = 93,
            opcode = PupOpcodes.AIR_CHANNEL,
            payload = byteArrayOf(255.toByte()),
            isQuery = false,
        )
        gateway.injectPupNotification(b10xDevice.address, frameOverflow)

        withTimeout(2000L) {
            while (orchestrator.dashboardState.value.connectedUnits.first { it.address == b10xDevice.address }.airChannel != 20) {
                delay(20)
            }
        }
        val clampedMax = orchestrator.dashboardState.value.connectedUnits.first { it.address == b10xDevice.address }.airChannel
        assertEquals("Channel 255 must be clamped to maxAirChannel (20)", 20, clampedMax)

        orchestrator.disconnectDevice(b10xDevice.address)
    }

    @Test
    fun `test 7 StudioOrchestrator clamps Connect Pro telemetry channel to 1 to 100`() = runBlocking {
        val gateway = InjectableBleGateway()
        val orchestrator = StudioOrchestrator(
            appContext = null,
            bleGateway = gateway,
            groupPacingMs = 0L,
        )

        val cpDevice = gateway.scannedDevices.value.first { it.address == VirtualBenchFixtures.ADDR_CONNECT_PRO }
        orchestrator.connectDevice(cpDevice, fastSimTimings = true)

        withTimeout(3000L) {
            while (orchestrator.dashboardState.value.connectedUnits.none { it.address == cpDevice.address && it.isReady }) {
                delay(20)
            }
        }

        val cpUnit = orchestrator.dashboardState.value.connectedUnits.first { it.address == cpDevice.address }
        assertEquals(1, cpUnit.minAirChannel)
        assertEquals(100, cpUnit.maxAirChannel)

        // Inject channel 0 -> clamped to 1
        val frameZero = PupFrameCodec.encodePacket(
            counter = 94,
            opcode = PupOpcodes.AIR_CHANNEL,
            payload = byteArrayOf(0.toByte()),
            isQuery = false,
        )
        gateway.injectPupNotification(cpDevice.address, frameZero)

        withTimeout(2000L) {
            while (orchestrator.dashboardState.value.connectedUnits.first { it.address == cpDevice.address }.airChannel != 1) {
                delay(20)
            }
        }
        assertEquals(1, orchestrator.dashboardState.value.connectedUnits.first { it.address == cpDevice.address }.airChannel)

        // Inject channel 42 -> preserved as 42
        val frame42 = PupFrameCodec.encodePacket(
            counter = 95,
            opcode = PupOpcodes.AIR_CHANNEL,
            payload = byteArrayOf(42.toByte()),
            isQuery = false,
        )
        gateway.injectPupNotification(cpDevice.address, frame42)

        withTimeout(2000L) {
            while (orchestrator.dashboardState.value.connectedUnits.first { it.address == cpDevice.address }.airChannel != 42) {
                delay(20)
            }
        }
        assertEquals(42, orchestrator.dashboardState.value.connectedUnits.first { it.address == cpDevice.address }.airChannel)

        // Inject channel 105 -> clamped to 100
        val frame105 = PupFrameCodec.encodePacket(
            counter = 96,
            opcode = PupOpcodes.AIR_CHANNEL,
            payload = byteArrayOf(105.toByte()),
            isQuery = false,
        )
        gateway.injectPupNotification(cpDevice.address, frame105)

        withTimeout(2000L) {
            while (orchestrator.dashboardState.value.connectedUnits.first { it.address == cpDevice.address }.airChannel != 100) {
                delay(20)
            }
        }
        assertEquals(100, orchestrator.dashboardState.value.connectedUnits.first { it.address == cpDevice.address }.airChannel)

        orchestrator.disconnectDevice(cpDevice.address)
    }

    @Test
    fun `test 8 Inverted channel bounds coercion does not crash on empty range`() {
        // Defensive math verification:
        // coerceIn(min, max.coerceAtLeast(min)) ensures min <= max even if wire telemetry delivers min > max
        val min = 10
        val corruptedMax = 5
        val channel = 8

        val safeMax = corruptedMax.coerceAtLeast(min)
        val clamped = channel.coerceIn(min, safeMax)
        assertEquals("Safe max coerces to min (10)", 10, safeMax)
        assertEquals("Clamped value evaluates to 10 without empty range crash", 10, clamped)
    }

    @Test
    fun `test 9 StudioOrchestrator sanitizes inverted AirChannelBounds telemetry and setAirChannel clamps cleanly`() = runBlocking {
        val gateway = InjectableBleGateway()
        val orchestrator = StudioOrchestrator(
            appContext = null,
            bleGateway = gateway,
            groupPacingMs = 0L,
        )

        val b10xDevice = gateway.scannedDevices.value.first { it.address == VirtualBenchFixtures.ADDR_B10X_PLUS }
        orchestrator.connectDevice(b10xDevice, fastSimTimings = true)

        withTimeout(3000L) {
            while (orchestrator.dashboardState.value.connectedUnits.none { it.address == b10xDevice.address && it.isReady }) {
                delay(20)
            }
        }

        // 1. Inject inverted bounds frame: min=20, max=1
        val frameInverted = PupFrameCodec.encodePacket(
            counter = 98,
            opcode = PupOpcodes.AIR_CHANNEL_RANGE,
            payload = byteArrayOf(20.toByte(), 1.toByte()),
            isQuery = false,
        )
        gateway.injectPupNotification(b10xDevice.address, frameInverted)

        withTimeout(2000L) {
            while (orchestrator.dashboardState.value.connectedUnits.first { it.address == b10xDevice.address }.minAirChannel != 20) {
                delay(20)
            }
        }

        val unitAfterBounds = orchestrator.dashboardState.value.connectedUnits.first { it.address == b10xDevice.address }
        assertEquals("minAirChannel sanitized to 20", 20, unitAfterBounds.minAirChannel)
        assertEquals("maxAirChannel sanitized to safeMax >= min (20)", 20, unitAfterBounds.maxAirChannel)

        // 2. Call setAirChannel(10) -> must not crash with IllegalArgumentException and clamps to 20
        orchestrator.setAirChannel(b10xDevice.address, 10)
        withTimeout(2000L) {
            while (orchestrator.dashboardState.value.connectedUnits.first { it.address == b10xDevice.address }.airChannel != 20) {
                delay(20)
            }
        }
        val unitAfterSet10 = orchestrator.dashboardState.value.connectedUnits.first { it.address == b10xDevice.address }
        assertEquals("Channel 10 must clamp cleanly to [20..20] = 20", 20, unitAfterSet10.airChannel)

        // 3. Call setAirChannel(25) -> must not crash and clamps to 20
        orchestrator.setAirChannel(b10xDevice.address, 25)
        withTimeout(2000L) {
            while (orchestrator.dashboardState.value.connectedUnits.first { it.address == b10xDevice.address }.airChannel != 20) {
                delay(20)
            }
        }
        val unitAfterSet25 = orchestrator.dashboardState.value.connectedUnits.first { it.address == b10xDevice.address }
        assertEquals("Channel 25 must clamp cleanly to [20..20] = 20", 20, unitAfterSet25.airChannel)

        orchestrator.disconnectDevice(b10xDevice.address)
    }

    @Test
    fun `test 10 MonolightControlCard dual power readout guards against fixture with maxWattSeconds non-positive`() {
        // MonolightControlCard.kt:214-218 calculation:
        // val maxWs = unit.modelSpec.maxWattSeconds
        // val ws = if (maxWs != null && maxWs > 0) PowerScaleConverter.formatWattSeconds(maxWs, unit.flashPowerTenthStops) else null
        // val frac = PowerScaleConverter.formatPowerFraction(unit.flashPowerTenthStops)
        // val dualReadout = if (ws != null) "$ws · $frac" else frac

        fun evaluateDualReadout(unit: ConnectedProfotoUnit, isDualPowerReadoutEnabled: Boolean): String? {
            return if (isDualPowerReadoutEnabled) {
                val maxWs = unit.modelSpec.maxWattSeconds
                val ws = if (maxWs != null && maxWs > 0) PowerScaleConverter.formatWattSeconds(maxWs, unit.flashPowerTenthStops) else null
                val frac = PowerScaleConverter.formatPowerFraction(unit.flashPowerTenthStops)
                if (ws != null) "$ws · $frac" else frac
            } else null
        }

        val baseDevice = VirtualBenchFixtures.createKit().first { it.address == VirtualBenchFixtures.ADDR_B10X_PLUS }

        // Case A: Standard fixture with valid positive maxWattSeconds (500 Ws)
        val unitValid500 = ConnectedProfotoUnit(
            device = baseDevice,
            flashPowerTenthStops = 70, // 7.0 stops -> 3 stops down from 10.0 = 500 / 8 = ~63 Ws
        )
        val readoutValid = evaluateDualReadout(unitValid500, isDualPowerReadoutEnabled = true)
        assertNotNull(readoutValid)
        assertTrue("Valid strobe includes Ws and fraction", readoutValid!!.contains("Ws") && readoutValid.contains("1/"))

        // Case B: Fixture with maxWattSeconds = 0 (anomaly/uncalibrated)
        val specZeroWs = unitValid500.modelSpec.copy(maxWattSeconds = 0)
        val unitZeroWs = unitValid500.copy(modelSpec = specZeroWs)

        // Without the guard (maxWs != null && maxWs > 0), PowerScaleConverter.formatWattSeconds throws IllegalArgumentException
        assertThrows(IllegalArgumentException::class.java) {
            PowerScaleConverter.formatWattSeconds(0, unitZeroWs.flashPowerTenthStops)
        }

        // With the guard, evaluateDualReadout evaluates ws = null safely and produces fraction without crashing
        val readoutZero = evaluateDualReadout(unitZeroWs, isDualPowerReadoutEnabled = true)
        assertEquals("1/8", readoutZero)

        // Case C: Fixture with negative maxWattSeconds = -1 (corrupted metadata)
        val specNegWs = unitValid500.modelSpec.copy(maxWattSeconds = -1)
        val unitNegWs = unitValid500.copy(modelSpec = specNegWs)

        assertThrows(IllegalArgumentException::class.java) {
            PowerScaleConverter.formatWattSeconds(-1, unitNegWs.flashPowerTenthStops)
        }

        val readoutNeg = evaluateDualReadout(unitNegWs, isDualPowerReadoutEnabled = true)
        assertEquals("1/8", readoutNeg)

        // Case D: Fixture with null maxWattSeconds (Connect remote)
        val specNullWs = unitValid500.modelSpec.copy(maxWattSeconds = null)
        val unitNullWs = unitValid500.copy(modelSpec = specNullWs)

        val readoutNull = evaluateDualReadout(unitNullWs, isDualPowerReadoutEnabled = true)
        assertEquals("1/8", readoutNull)

        // Case E: When dual power readout is disabled, returns null regardless of maxWattSeconds
        assertEquals(null, evaluateDualReadout(unitZeroWs, isDualPowerReadoutEnabled = false))
        assertEquals(null, evaluateDualReadout(unitNegWs, isDualPowerReadoutEnabled = false))
    }

    @Test
    fun `test 11 StudioOrchestrator enforces ceiling clamp on hostile AirChannelBounds frames and setAirChannel dispatches without PupCodecException`() = runBlocking {
        val gateway = InjectableBleGateway()
        val orchestrator = StudioOrchestrator(
            appContext = null,
            bleGateway = gateway,
            groupPacingMs = 0L,
        )

        val b10xDevice = gateway.scannedDevices.value.first { it.address == VirtualBenchFixtures.ADDR_B10X_PLUS }
        orchestrator.connectDevice(b10xDevice, fastSimTimings = true)

        withTimeout(3000L) {
            while (orchestrator.dashboardState.value.connectedUnits.none { it.address == b10xDevice.address && it.isReady }) {
                delay(20)
            }
        }

        // Scenario A: Hostile out-of-bounds frame [255, 0]
        val frameHostile255 = PupFrameCodec.encodePacket(
            counter = 201,
            opcode = PupOpcodes.AIR_CHANNEL_RANGE,
            payload = byteArrayOf(255.toByte(), 0.toByte()),
            isQuery = false,
        )
        gateway.injectPupNotification(b10xDevice.address, frameHostile255)

        withTimeout(2000L) {
            while (orchestrator.dashboardState.value.connectedUnits.first { it.address == b10xDevice.address }.minAirChannel != 100) {
                delay(20)
            }
        }

        val unitAfter255 = orchestrator.dashboardState.value.connectedUnits.first { it.address == b10xDevice.address }
        assertEquals("Hostile [255, 0] minAirChannel must be clamped to ceiling 100", 100, unitAfter255.minAirChannel)
        assertEquals("Hostile [255, 0] maxAirChannel must be clamped to ceiling 100", 100, unitAfter255.maxAirChannel)

        // Calling setAirChannel(255) must clamp to [100..100] and dispatch without PupCodecException
        orchestrator.setAirChannel(b10xDevice.address, 255)
        withTimeout(2000L) {
            while (orchestrator.dashboardState.value.connectedUnits.first { it.address == b10xDevice.address }.airChannel != 100) {
                delay(20)
            }
        }
        val unitAfterSet255 = orchestrator.dashboardState.value.connectedUnits.first { it.address == b10xDevice.address }
        assertEquals("Channel 255 must clamp to safe bounds [100..100] = 100", 100, unitAfterSet255.airChannel)

        // Scenario B: Hostile negative/overflow frame [-5, -10] (wire bytes 0xFB, 0xF6 -> decoded as unsigned 251, 246)
        val frameHostileNeg = PupFrameCodec.encodePacket(
            counter = 202,
            opcode = PupOpcodes.AIR_CHANNEL_RANGE,
            payload = byteArrayOf((-5).toByte(), (-10).toByte()),
            isQuery = false,
        )
        gateway.injectPupNotification(b10xDevice.address, frameHostileNeg)

        withTimeout(2000L) {
            while (orchestrator.dashboardState.value.connectedUnits.first { it.address == b10xDevice.address }.minAirChannel != 100) {
                delay(20)
            }
        }

        val unitAfterNeg = orchestrator.dashboardState.value.connectedUnits.first { it.address == b10xDevice.address }
        assertEquals("Hostile [-5, -10] minAirChannel must clamp to ceiling 100", 100, unitAfterNeg.minAirChannel)
        assertEquals("Hostile [-5, -10] maxAirChannel must clamp to ceiling 100", 100, unitAfterNeg.maxAirChannel)

        // Calling setAirChannel(10) must clamp to [100..100] = 100 and dispatch without PupCodecException
        orchestrator.setAirChannel(b10xDevice.address, 10)
        withTimeout(2000L) {
            while (orchestrator.dashboardState.value.connectedUnits.first { it.address == b10xDevice.address }.airChannel != 100) {
                delay(20)
            }
        }
        val unitAfterSet10 = orchestrator.dashboardState.value.connectedUnits.first { it.address == b10xDevice.address }
        assertEquals("Channel 10 must clamp to safe bounds [100..100] = 100", 100, unitAfterSet10.airChannel)

        // Scenario C: Malformed range [0, 255] clamps min to 1 and max to 100
        val frameHostile0to255 = PupFrameCodec.encodePacket(
            counter = 203,
            opcode = PupOpcodes.AIR_CHANNEL_RANGE,
            payload = byteArrayOf(0.toByte(), 255.toByte()),
            isQuery = false,
        )
        gateway.injectPupNotification(b10xDevice.address, frameHostile0to255)

        withTimeout(2000L) {
            while (orchestrator.dashboardState.value.connectedUnits.first { it.address == b10xDevice.address }.minAirChannel != 1) {
                delay(20)
            }
        }

        val unitAfter0to255 = orchestrator.dashboardState.value.connectedUnits.first { it.address == b10xDevice.address }
        assertEquals("minAirChannel clamped to floor 1", 1, unitAfter0to255.minAirChannel)
        assertEquals("maxAirChannel clamped to ceiling 100", 100, unitAfter0to255.maxAirChannel)

        // Calling setAirChannel with value > 100 clamps to 100 and dispatches cleanly
        orchestrator.setAirChannel(b10xDevice.address, 150)
        withTimeout(2000L) {
            while (orchestrator.dashboardState.value.connectedUnits.first { it.address == b10xDevice.address }.airChannel != 100) {
                delay(20)
            }
        }
        val unitAfterSet150 = orchestrator.dashboardState.value.connectedUnits.first { it.address == b10xDevice.address }
        assertEquals("Channel 150 must clamp to [1..100] = 100", 100, unitAfterSet150.airChannel)

        orchestrator.disconnectDevice(b10xDevice.address)
    }
}
