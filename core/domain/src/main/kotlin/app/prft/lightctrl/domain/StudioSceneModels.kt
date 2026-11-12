package app.prft.lightctrl.domain

import app.prft.lightctrl.protocol.AfAssistMode
import app.prft.lightctrl.protocol.AirGroup
import app.prft.lightctrl.protocol.ReadySignalMode
import app.prft.lightctrl.protocol.RemoteOperatingMode
import app.prft.lightctrl.protocol.RfPowerLevel
import app.prft.lightctrl.protocol.SyncTriggerMode
import java.util.UUID

/**
 * Complete studio lighting scene / preset capturing group power levels,
 * modeling lamp states, role labels, setup notes, and trigger parameters.
 */
data class StudioScene(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAtUtc: Long = System.currentTimeMillis(),
    val setupNotes: String = "",
    val groupPresets: List<GroupPreset> = emptyList(),
    val remoteChannel: Int? = null,
    val remoteOperatingMode: RemoteOperatingMode? = null,
    val syncTriggerMode: SyncTriggerMode? = null,
    val afAssistMode: AfAssistMode? = null,
    val rfPowerLevel: RfPowerLevel? = null,
    val readySignalMode: ReadySignalMode? = null,
) {
    fun presetFor(group: AirGroup): GroupPreset? =
        groupPresets.firstOrNull { it.group == group }
}

/**
 * Lighting parameters for a single AirX group (A..F).
 */
data class GroupPreset(
    val group: AirGroup,
    val powerTenthStops: Int = 70, // 7.0 stops default
    val isEnabled: Boolean = true,
    val isModelingEnabled: Boolean = false,
    val roleLabel: String? = null,
    val ttlEvBiasTenthStops: Int? = null, // Opcode 131 per-group bias for A..C (-20..+20)
) {
    val fStops: Float get() = powerTenthStops / 10f
    val ttlEvBias: Float? get() = ttlEvBiasTenthStops?.let { it / 10f }
}
