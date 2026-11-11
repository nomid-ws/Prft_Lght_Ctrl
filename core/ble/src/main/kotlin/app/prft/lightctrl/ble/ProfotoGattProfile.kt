package app.prft.lightctrl.ble

import java.util.UUID

/**
 * Authoritative Profoto BLE GATT UUID registry with strict DFU/bootloader quarantine.
 */
object ProfotoGattProfile {

    /** Bluetooth SIG 16-bit Service (0xFDBB) used by B10X, B10X Plus, A2, A10, Pro-D3, Connect Pro. */
    val PRIMARY_SIG_SERVICE_UUID: UUID =
        UUID.fromString("0000fdbb-0000-1000-8000-00805f9b34fb")

    /** Legacy 128-bit Base Service used by original Profoto Connect and early B10 / C1 units. */
    val LEGACY_BASE_SERVICE_UUID: UUID =
        UUID.fromString("1d944db4-58a0-4177-84da-e29bf5c4630d")

    /** Dedicated application client-authentication GATT service on AirX firmware (A2, B10X, Connect Pro). */
    val AUTH_SERVICE_UUID: UUID =
        UUID.fromString("bbd75314-ddaa-4b61-a7df-c6eedb79c152")

    /** Application-layer client authentication characteristic (expects UTF-8 "Profoto Client"). */
    val AUTH_CLIENT_ID_CHAR_UUID: UUID =
        UUID.fromString("c655ad5a-f991-4ce6-8aaf-10803359b587")

    /** PUP Host -> Peripheral TX pipe (Write Without Response). */
    val PUP_TX_CHAR_UUID: UUID =
        UUID.fromString("64ecef4f-4408-4565-b017-f254e64462db")

    /** PUP Peripheral -> Host RX notification stream (Notify). */
    val PUP_RX_CHAR_UUID: UUID =
        UUID.fromString("049e2ea9-516a-420b-99f9-b28619f965aa")

    /** Standard Bluetooth SIG Client Characteristic Configuration Descriptor (0x2902). */
    val CCCD_UUID: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    /** Handshake token required on AUTH_CLIENT_ID_CHAR_UUID to unlock PUP TX/RX. */
    const val HANDSHAKE_TOKEN_ASCII: String = "Profoto Client"
    val HANDSHAKE_TOKEN_BYTES: ByteArray
        get() = HANDSHAKE_TOKEN_ASCII.toByteArray(Charsets.UTF_8)

    const val TARGET_MTU: Int = 255
    const val GATT_CACHE_SETTLE_MS: Long = 800L
    const val STAGE_WATCHDOG_TIMEOUT_MS: Long = 8_000L
    const val STANDARD_ACK_TIMEOUT_MS: Long = 350L
    const val EXTENDED_ACK_TIMEOUT_MS: Long = 700L
    const val MAX_COMMAND_RETRIES: Int = 3
    const val GROUP_INTER_DEVICE_PACING_MS: Long = 30L

    /** Strictly quarantined OTA / DFU GATT service UUIDs (Silabs OTA & Profoto DFU V1/V2). */
    val FORBIDDEN_DFU_SERVICE_UUIDS: Set<UUID> = setOf(
        UUID.fromString("1d14d6ee-fd63-4fa1-bfa4-8f47b42119f0"), // Silabs BLE OTA Service
        UUID.fromString("26427d9c-9a9d-461b-bb51-688a00c603c0"), // Profoto Firmware DFU Service
    )

    /**
     * Strictly quarantined DFU & bootloader GATT characteristics.
     * Accessing or writing these characteristics can reboot hardware into bootloader recovery mode.
     */
    val FORBIDDEN_DFU_CHAR_UUIDS: Set<UUID> = setOf(
        UUID.fromString("f7bf3564-fb6d-4e53-88a4-5e37e0326063"), // MCU DFU Bootloader Reboot
        UUID.fromString("a28ee0b7-3f08-45a6-95a5-cbf5ee07c7d6"), // DFU V2 Control/Status
        UUID.fromString("e5bb8327-628c-4e9d-8149-53fdbbc7926d"), // DFU V2 Block Control
        UUID.fromString("e5bb8327-c0b0-42a2-99bd-e61fb22a5a05"), // DFU Block Data Stream
        UUID.fromString("12098741-7a65-4ce3-b45a-b547ef487a11"), // DFU V1 Data
        UUID.fromString("25f05c0a-e917-46e9-b2a5-aa2be1245afe"), // DFU V1 Status
        UUID.fromString("984227f3-34fc-4045-a5d0-2c581f81a153"), // Silabs OTA Data
        UUID.fromString("4f4a2368-8cca-451e-bfff-cf0e2ee23e9f"), // DFU V1 Header
    )

    fun getAllManagedServiceUuids(): Set<UUID> = setOf(
        PRIMARY_SIG_SERVICE_UUID,
        LEGACY_BASE_SERVICE_UUID,
        AUTH_SERVICE_UUID,
    )

    fun getAllManagedCharacteristicUuids(): Set<UUID> = setOf(
        AUTH_CLIENT_ID_CHAR_UUID,
        PUP_TX_CHAR_UUID,
        PUP_RX_CHAR_UUID,
    )

    fun assertSafeCharacteristicUuid(uuid: UUID) {
        if (uuid in FORBIDDEN_DFU_CHAR_UUIDS) {
            throw SecurityException("CRITICAL DFU QUARANTINE VIOLATION: Blocked access to DFU UUID $uuid")
        }
    }
}
