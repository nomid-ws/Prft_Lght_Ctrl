package app.prft.lightctrl.protocol

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class TlvEntry(
    val length: Int,
    val opcode: Int,
    val payload: ByteArray,
    val decoded: PupDecodedMessage?,
)

data class AllSettingsV2Result(
    val entries: List<TlvEntry>,
    val finished: Boolean,
)

/**
 * Streaming TLV parser and serializer for AllSettingsV2 (Opcode 124 / 0x007C).
 *
 * Record layout:
 *   - Byte 0: entry_len (uint8)
 *       0 -> End-of-stream marker (finished = true)
 *       1 -> Malformed record (throws PupCodecException)
 *      >=2 -> [subOpcode: uint16_le][subPayload: entry_len - 2 bytes]
 */
object AllSettingsTlvParser {

    fun parse(payload: ByteArray): AllSettingsV2Result {
        val entries = mutableListOf<TlvEntry>()
        var offset = 0
        val total = payload.size
        var finished = false

        while (offset < total) {
            val entryLen = payload[offset].toInt() and 0xFF
            offset += 1

            if (entryLen == 0) {
                finished = true
                break
            }
            if (entryLen == 1) {
                throw PupCodecException("Invalid len=1 in AllSettingsV2 TLV at offset ${offset - 1}")
            }
            if (offset + entryLen > total) {
                throw PupCodecException(
                    "Truncated AllSettingsV2 TLV at offset ${offset - 1}: need $entryLen bytes, have ${total - offset}"
                )
            }

            val subOpcode = PupFrameCodec.readUInt16Le(payload, offset)
            val subPayload = payload.copyOfRange(offset + 2, offset + entryLen)
            offset += entryLen

            val decoded = try {
                PupFrameCodec.decodePayload(subOpcode, subPayload, isQuery = false)
            } catch (_: Exception) {
                null
            }

            entries.add(
                TlvEntry(
                    length = entryLen,
                    opcode = subOpcode,
                    payload = subPayload,
                    decoded = decoded,
                )
            )
        }

        return AllSettingsV2Result(entries = entries, finished = finished)
    }

    fun serialize(records: List<Pair<Int, ByteArray>>, terminate: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        for ((subOpcode, subPayload) in records) {
            if ((subOpcode and PupConstants.BASE_OPCODE_MASK) in PupConstants.QUARANTINED_OPCODES) {
                throw PupSecurityQuarantineException(subOpcode)
            }
            val entryLen = 2 + subPayload.size
            if (entryLen > 255) {
                throw PupCodecException("AllSettingsV2 TLV record exceeds 255 bytes: $entryLen")
            }
            out.write(entryLen)
            val header = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(subOpcode.toShort()).array()
            out.write(header)
            out.write(subPayload)
        }
        if (terminate) {
            out.write(0)
        }
        return out.toByteArray()
    }
}
