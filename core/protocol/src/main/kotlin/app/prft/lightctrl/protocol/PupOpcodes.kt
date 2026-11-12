package app.prft.lightctrl.protocol

/**
 * Authoritative Profoto Universal Protocol (PUP) wire constants and opcodes.
 *
 * Wire Frame Format:
 *   Byte 0:   Sequence Counter (uint8 modulo 240, 0..239)
 *   Byte 1-2: Opcode (uint16_le; bit 15 / 0x8000 = Query flag)
 *   Byte 3..N: Little-endian payload
 */
object PupConstants {
    const val PACKET_HEADER_SIZE: Int = 3
    const val SEQUENCE_MODULO: Int = 240
    const val QUERY_BIT_MASK: Int = 0x8000
    const val BASE_OPCODE_MASK: Int = 0x7FFF

    /**
     * Strictly quarantined firmware/DFU opcodes that must NEVER be transmitted.
     * - 46 (0x002E): FirmwareArm
     * - 142 (0x008E): BlobData (firmware block transfer)
     * - 154 (0x009A): arm_firmware_update
     */
    val QUARANTINED_OPCODES: Set<Int> = setOf(46, 142, 154)
}

object PupOpcodes {
    // Flow Control
    const val INVALID_MESSAGE = 0
    const val ACK = 1
    const val NACK = 2

    // Air Radio & Group Sync
    const val AIR_SYNC_ENABLE = 9
    const val AIR_GROUP = 10
    const val AIR_CHANNEL = 11
    const val AIR_HSS_ENABLE = 12
    const val AIR_HSS_PULSE_LENGTH = 13
    const val AIR_HSS_LENGTH_RANGE = 14
    const val AIR_CHANNEL_RANGE = 16
    const val AIR_GROUP_RANGE = 17

    // Flash Output & Strobe Controls
    const val MX_ENERGY = 18
    const val MX_ENERGY_RANGE = 19
    const val PX_ENERGY = 20
    const val PX_ENERGY_RANGE = 21
    const val FREEZE_ENABLE = 22
    const val HEAD_ON = 23
    const val DIM_DURING_RECHARGE = 24
    const val SOUND_ENABLE_READY = 25
    const val IR_SLAVE_ENABLE = 26
    const val GET_ALL_SETTINGS = 27
    const val ALL_SETTINGS_LEGACY = 28

    // Continuous / Modeling LED (A2, B10, B10X, B10X Plus)
    const val VIDEO_LIGHT_ENABLE = 31
    const val VIDEO_LIGHT_POWER_MODE = 32
    const val VIDEO_LIGHT_INTENSITY = 33
    const val VIDEO_LIGHT_INTENSITY_RANGE = 35
    const val VIDEO_LIGHT_COLOR_TEMPERATURE = 36
    const val VIDEO_LIGHT_COLOR_RANGE = 37

    // Acoustic, Display & Power-Save
    const val CONTROL_SOUNDS_ENABLE = 38
    const val DISPLAY_BRIGHTNESS = 39
    const val AUTO_STANDBY_TIMEOUT = 40
    const val AUTO_OFF_TIMEOUT = 41
    const val WARNING_SOUNDS_ENABLE = 42
    const val TTL_AUTO_ENABLED = 43
    const val TTL_EXPOSURE_WARNING_ENABLED = 44
    const val TEST_FLASH = 45

    // Quarantined Firmware Arm (Blocked)
    const val FIRMWARE_ARM_QUARANTINED = 46

    // Identity, Battery & Telemetry
    const val BLE_DEVICE_NAME = 64
    const val BATTERY_INDICATOR = 70
    const val FIRMWARE_VERSION_GIT = 71
    const val FIRMWARE_VERSION_A6 = 72
    const val SERIAL_NUMBER = 73
    const val HARDWARE_ID = 74

    // Legacy V1 Explicit Query Opcodes (Used by Profoto Connect & early B10)
    const val QUERY_FIRMWARE_GIT = 76       // Queries 71
    const val QUERY_FIRMWARE_A6 = 77        // Queries 72
    const val QUERY_SERIAL_NUMBER = 78      // Queries 73
    const val QUERY_HARDWARE_ID = 79        // Queries 74
    const val QUERY_BATTERY_INDICATOR = 80  // Queries 70
    const val QUERY_BLE_DEVICE_NAME = 81    // Queries 64
    const val QUERY_AUTO_OFF_TIMEOUT = 83   // Queries 41
    const val QUERY_TTL_AUTO_ENABLED = 87   // Queries 43
    const val QUERY_AIR_GROUP = 106         // Queries 10
    const val QUERY_AIR_CHANNEL = 107       // Queries 11
    const val QUERY_AIR_CHANNEL_RANGE = 119 // Queries 16

    // Charger, Mode & Bulk V2 Dump
    const val BATTERY_CHARGER_CONNECTED = 112
    const val CAPTURE_MODE = 117
    const val HARDWARE_REVISION = 118
    const val ALL_SETTINGS_V2 = 124

    // Remote / AirTTL / Connect & Connect Pro Opcodes
    const val AIR_PX_BIAS = 131
    const val ADJUST_AIR_MX_ENERGY_IF_ABLE = 135
    const val ADJUST_AIR_MX_ENERGY_RESPONSE = 136
    const val AIR_HEAD_ON = 137
    const val AIR_MODEL_LIGHT_ON = 138
    const val AIR_TTL_EV_COMP = 139
    const val SYNC_MODE = 140
    const val BLOB_DATA_QUARANTINED = 142
    const val REMOTE_MODE = 144
    const val RMI_RF_POWER = 147
    const val RADIO_MODE_RANGE = 148
    const val ARM_FW_UPDATE_QUARANTINED = 154
    const val AIR_SYNC_MODE = 156
    const val AF_ASSIST_MODE = 160
    const val ZOOM_MODE = 161
    const val ZOOM_RANGE = 162
    const val ZOOM_VALUE = 163
    const val READY_SIGNAL_MODE = 217
    const val CCT_OUTPUT_MODE = 217

    // Recycle, Misfire & AirX Multi-Group Control
    const val FLASH_SCHEDULING_RECEIVED_EVT = 169
    const val FLASH_FIRED_EVT = 171
    const val AIR_ADJUST_ENERGY = 174
    const val AIR_ADJUST_ENERGY_STATUS = 175
    const val AIR_ABSOLUTE_GROUP_ENERGY = 177
    const val AIR_PROTOCOL_MODE = 178
    const val BATTERY_TYPE = 180
    const val AUTO_ON = 183
    const val DISPLAY_ORIENTATION = 184
    const val FLASH_MODE = 186

    // Multi-Head V2 Opcodes (AirX V2 / Pro-11 / Multi-Socket Packs)
    const val AIR_GROUP_V2 = 801
    const val HEAD_ON_V2 = 802
    const val MX_ENERGY_V2 = 803
    const val MX_ENERGY_CONSTRAINT = 804
    const val VIDEO_LIGHT_ENABLE_V2 = 805
    const val CHARGER_SPEED = 806
    const val HEAD_CONNECTED = 807

    /** Non-ackable fire-and-forget / query / event opcodes per F2/C0190j.m(int). */
    val NON_ACKABLE_OPCODES: Set<Int> = setOf(
        INVALID_MESSAGE,
        ACK,
        NACK,
        GET_ALL_SETTINGS,
        TEST_FLASH,
        QUERY_FIRMWARE_GIT,
        QUERY_FIRMWARE_A6,
        QUERY_SERIAL_NUMBER,
        QUERY_HARDWARE_ID,
        QUERY_BATTERY_INDICATOR,
        QUERY_BLE_DEVICE_NAME,
        QUERY_AUTO_OFF_TIMEOUT,
        QUERY_TTL_AUTO_ENABLED,
        QUERY_AIR_GROUP,
        QUERY_AIR_CHANNEL,
        QUERY_AIR_CHANNEL_RANGE,
        ADJUST_AIR_MX_ENERGY_IF_ABLE,
        FLASH_SCHEDULING_RECEIVED_EVT,
        FLASH_FIRED_EVT,
    )
}

enum class AirGroup(val code: Int, val label: String, val bitIndex: Int) {
    GROUP_A(1, "A", 0),
    GROUP_B(2, "B", 1),
    GROUP_C(3, "C", 2),
    GROUP_D(4, "D", 3),
    GROUP_E(5, "E", 4),
    GROUP_F(6, "F", 5),
    ALL(7, "ALL", -1);

    companion object {
        fun fromCode(code: Int): AirGroup = when (code) {
            0, 1 -> GROUP_A
            2 -> GROUP_B
            3 -> GROUP_C
            4 -> GROUP_D
            5 -> GROUP_E
            6 -> GROUP_F
            7 -> ALL
            else -> throw PupCodecException("Invalid AirGroup code: $code (allowed 1..7)")
        }
    }
}

enum class AirProtocolMode(val code: Int, val label: String) {
    AIR_1(0, "Air 1 (Ch 1–20)"),
    AIR_2(1, "Air 2 / AirX (Ch 1–100)");

    companion object {
        fun fromCode(code: Int): AirProtocolMode =
            entries.firstOrNull { it.code == code } ?: AIR_2
    }
}

enum class VideoLightPowerMode(val code: Int, val label: String) {
    FREE(0, "Free"),
    PROPORTIONAL(1, "Proportional"),
    FLICKER_FREE(2, "Flicker-Free"),
    MIN(3, "Min"),
    MAX(4, "Max"),
    PROPORTIONAL_MAX(5, "Prop Max");

    companion object {
        fun fromCode(code: Int): VideoLightPowerMode =
            entries.firstOrNull { it.code == code } ?: FREE
    }
}

enum class FlashMode(val code: Int, val label: String) {
    MANUAL(0, "Manual"),
    TTL(1, "TTL");

    companion object {
        fun fromCode(code: Int): FlashMode =
            if (code == 1) TTL else MANUAL
    }
}

enum class RemoteOperatingMode(val code: Int, val label: String) {
    OFF(0, "Off"),
    MANUAL(1, "Manual"),
    AUTO_TTL(2, "Auto (TTL)");

    companion object {
        fun fromCode(code: Int): RemoteOperatingMode =
            entries.firstOrNull { it.code == code } ?: AUTO_TTL
    }
}

enum class DisplayBrightnessLevel(val code: Int, val label: String) {
    LOW(0, "Low"),
    MEDIUM(1, "Med"),
    HIGH(2, "High"),
    AUTO(3, "Auto");

    companion object {
        fun fromCode(code: Int): DisplayBrightnessLevel =
            entries.firstOrNull { it.code == code } ?: HIGH
    }
}

enum class DisplayOrientationMode(val code: Int, val label: String) {
    AUTO(0, "Auto"),
    NORMAL(1, "Normal"),
    INVERTED(2, "Inverted");

    companion object {
        fun fromCode(code: Int): DisplayOrientationMode =
            entries.firstOrNull { it.code == code } ?: AUTO
    }
}

enum class BatteryChemistry(val code: Int, val label: String) {
    AUTO_DETECT(0, "Auto"),
    NIMH(1, "NiMH"),
    ALKALINE(2, "Alkaline"),
    LITHIUM(3, "Lithium");

    companion object {
        fun fromCode(code: Int): BatteryChemistry =
            entries.firstOrNull { it.code == code } ?: AUTO_DETECT
    }
}

enum class FlashSchedulingState(val code: Int) {
    SCHEDULED_OK(0),
    SCHEDULED_TOO_SOON(1),
    INTERNAL_FLASH_ERROR(2);

    companion object {
        fun fromCode(code: Int): FlashSchedulingState =
            entries.firstOrNull { it.code == code }
                ?: throw PupCodecException("Invalid FlashSchedulingState: $code")
    }
}

enum class FlashFiredError(val code: Int, val description: String) {
    FLASH_BEFORE_READY(0, "Flash triggered before full charge"),
    FLASH_TERMINATED_EARLY(1, "Pulse clipped early (thermal/IGBT)"),
    FLASH_ENERGY_NOT_REACHED(2, "Target capacitor energy not reached"),
    INTERNAL_ERROR(3, "Internal discharge fault");

    companion object {
        fun fromCode(code: Int): FlashFiredError? =
            entries.firstOrNull { it.code == code }
    }
}

enum class SyncTriggerMode(val code: Int, val label: String) {
    FIRST(0, "1st Curtain"),
    SECOND(1, "2nd Curtain"),
    X_SYNC(2, "X-Sync"),
    AUTO_FP(3, "Auto FP / HSS"),
    GLOBAL_SHUTTER(4, "Global Shutter");

    companion object {
        fun fromCode(code: Int): SyncTriggerMode =
            entries.firstOrNull { it.code == code } ?: FIRST
    }
}

enum class AfAssistMode(val code: Int, val label: String) {
    OFF(0, "Off"),
    AUTO(1, "Auto"),
    ON(2, "On");

    companion object {
        fun fromCode(code: Int): AfAssistMode =
            entries.firstOrNull { it.code == code } ?: OFF
    }
}

enum class RfPowerLevel(val code: Int, val label: String) {
    P1(1, "P1"),
    P2(2, "P2"),
    P3(3, "P3"),
    P4(4, "P4");

    companion object {
        fun fromCode(code: Int): RfPowerLevel =
            entries.firstOrNull { it.code == code } ?: P4
    }
}

enum class ReadySignalMode(val code: Int, val label: String) {
    OFF(0, "Off"),
    SOUND(1, "Sound"),
    DIM(2, "Dim"),
    SOUND_AND_DIM(3, "Sound & Dim");

    companion object {
        fun fromCode(code: Int): ReadySignalMode =
            entries.firstOrNull { it.code == code } ?: SOUND
    }
}

enum class MonolightZoomMode(val code: Int, val label: String) {
    MANUAL(0, "Manual"),
    AUTO(1, "Auto");

    companion object {
        fun fromCode(code: Int): MonolightZoomMode =
            entries.firstOrNull { it.code == code } ?: AUTO
    }
}
