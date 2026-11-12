package app.prft.lightctrl.domain

import app.prft.lightctrl.protocol.AfAssistMode
import app.prft.lightctrl.protocol.AirGroup
import app.prft.lightctrl.protocol.ReadySignalMode
import app.prft.lightctrl.protocol.RemoteOperatingMode
import app.prft.lightctrl.protocol.RfPowerLevel
import app.prft.lightctrl.protocol.SyncTriggerMode
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

class StudioSceneCodecException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

/**
 * Pure org.json serialization and deserialization for StudioScene presets.
 * Zero external libraries (no Gson, Jackson, Moshi). Resilient against missing
 * properties, format variations (array vs container object), and corrupted input.
 */
object StudioSceneJsonCodec {

    private const val SCHEMA_VERSION = 1

    private const val KEY_SCHEMA_VERSION = "schemaVersion"
    private const val KEY_SCENES = "scenes"
    private const val KEY_ID = "id"
    private const val KEY_NAME = "name"
    private const val KEY_CREATED_AT_UTC = "createdAtUtc"
    private const val KEY_SETUP_NOTES = "setupNotes"
    private const val KEY_GROUP_PRESETS = "groupPresets"
    private const val KEY_REMOTE_CHANNEL = "remoteChannel"
    private const val KEY_REMOTE_OPERATING_MODE = "remoteOperatingMode"
    private const val KEY_SYNC_TRIGGER_MODE = "syncTriggerMode"
    private const val KEY_AF_ASSIST_MODE = "afAssistMode"
    private const val KEY_RF_POWER_LEVEL = "rfPowerLevel"
    private const val KEY_READY_SIGNAL_MODE = "readySignalMode"

    private const val KEY_GROUP = "group"
    private const val KEY_POWER_TENTH_STOPS = "powerTenthStops"
    private const val KEY_IS_ENABLED = "isEnabled"
    private const val KEY_IS_MODELING_ENABLED = "isModelingEnabled"
    private const val KEY_ROLE_LABEL = "roleLabel"
    private const val KEY_TTL_EV_BIAS = "ttlEvBiasTenthStops"

    fun encodeScene(scene: StudioScene): JSONObject {
        val root = JSONObject()
        root.put(KEY_ID, scene.id)
        root.put(KEY_NAME, scene.name)
        root.put(KEY_CREATED_AT_UTC, scene.createdAtUtc)
        root.put(KEY_SETUP_NOTES, scene.setupNotes)
        if (scene.remoteChannel != null) root.put(KEY_REMOTE_CHANNEL, scene.remoteChannel)
        if (scene.remoteOperatingMode != null) root.put(KEY_REMOTE_OPERATING_MODE, scene.remoteOperatingMode.name)
        if (scene.syncTriggerMode != null) root.put(KEY_SYNC_TRIGGER_MODE, scene.syncTriggerMode.name)
        if (scene.afAssistMode != null) root.put(KEY_AF_ASSIST_MODE, scene.afAssistMode.name)
        if (scene.rfPowerLevel != null) root.put(KEY_RF_POWER_LEVEL, scene.rfPowerLevel.name)
        if (scene.readySignalMode != null) root.put(KEY_READY_SIGNAL_MODE, scene.readySignalMode.name)

        val groupsArray = JSONArray()
        for (gp in scene.groupPresets) {
            val gpObj = JSONObject()
            gpObj.put(KEY_GROUP, gp.group.label)
            gpObj.put(KEY_POWER_TENTH_STOPS, gp.powerTenthStops)
            gpObj.put(KEY_IS_ENABLED, gp.isEnabled)
            gpObj.put(KEY_IS_MODELING_ENABLED, gp.isModelingEnabled)
            if (!gp.roleLabel.isNullOrBlank()) gpObj.put(KEY_ROLE_LABEL, gp.roleLabel)
            if (gp.ttlEvBiasTenthStops != null) gpObj.put(KEY_TTL_EV_BIAS, gp.ttlEvBiasTenthStops)
            groupsArray.put(gpObj)
        }
        root.put(KEY_GROUP_PRESETS, groupsArray)
        return root
    }

    fun encodeSingleToJson(scene: StudioScene, indentSpaces: Int = 2): String =
        encodeScene(scene).toString(indentSpaces)

    fun encodeToJson(scenes: List<StudioScene>, indentSpaces: Int = 2): String {
        val root = JSONObject()
        root.put(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
        val arr = JSONArray()
        for (s in scenes) arr.put(encodeScene(s))
        root.put(KEY_SCENES, arr)
        return root.toString(indentSpaces)
    }

    fun decodeScene(obj: JSONObject): StudioScene {
        val id = obj.optString(KEY_ID).takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val name = obj.optString(KEY_NAME).takeIf { it.isNotBlank() } ?: "Untitled Preset"
        val createdAtUtc = obj.optLong(KEY_CREATED_AT_UTC, System.currentTimeMillis())
        val setupNotes = obj.optString(KEY_SETUP_NOTES, "")
        val remoteChannel = if (obj.has(KEY_REMOTE_CHANNEL) && !obj.isNull(KEY_REMOTE_CHANNEL)) {
            obj.optInt(KEY_REMOTE_CHANNEL).takeIf { it in 1..100 }
        } else {
            null
        }
        val remoteMode = obj.optString(KEY_REMOTE_OPERATING_MODE).takeIf { it.isNotBlank() }?.let { raw ->
            RemoteOperatingMode.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        }
        val syncMode = obj.optString(KEY_SYNC_TRIGGER_MODE).takeIf { it.isNotBlank() }?.let { raw ->
            SyncTriggerMode.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        }
        val afMode = obj.optString(KEY_AF_ASSIST_MODE).takeIf { it.isNotBlank() }?.let { raw ->
            AfAssistMode.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        }
        val rfPower = obj.optString(KEY_RF_POWER_LEVEL).takeIf { it.isNotBlank() }?.let { raw ->
            RfPowerLevel.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        }
        val readySignal = obj.optString(KEY_READY_SIGNAL_MODE).takeIf { it.isNotBlank() }?.let { raw ->
            ReadySignalMode.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        }

        val groupPresets = mutableListOf<GroupPreset>()
        val groupsArray = obj.optJSONArray(KEY_GROUP_PRESETS)
        if (groupsArray != null) {
            for (i in 0 until groupsArray.length()) {
                val gpObj = groupsArray.optJSONObject(i) ?: continue
                val groupStr = gpObj.optString(KEY_GROUP, "A").trim().uppercase()
                val group = AirGroup.entries.firstOrNull {
                    it.label.equals(groupStr, ignoreCase = true) ||
                    it.name.equals(groupStr, ignoreCase = true) ||
                    it.name.equals("GROUP_$groupStr", ignoreCase = true)
                } ?: AirGroup.GROUP_A
                val powerTenthStops = gpObj.optInt(KEY_POWER_TENTH_STOPS, 70).coerceIn(1, 100)
                val isEnabled = gpObj.optBoolean(KEY_IS_ENABLED, true)
                val isModelingEnabled = gpObj.optBoolean(KEY_IS_MODELING_ENABLED, false)
                val roleLabel = gpObj.optString(KEY_ROLE_LABEL).takeIf { it.isNotBlank() }
                val ttlEvBias = if (gpObj.has(KEY_TTL_EV_BIAS) && !gpObj.isNull(KEY_TTL_EV_BIAS)) {
                    gpObj.optInt(KEY_TTL_EV_BIAS).coerceIn(-20, 20)
                } else {
                    null
                }

                groupPresets.add(
                    GroupPreset(
                        group = group,
                        powerTenthStops = powerTenthStops,
                        isEnabled = isEnabled,
                        isModelingEnabled = isModelingEnabled,
                        roleLabel = roleLabel,
                        ttlEvBiasTenthStops = ttlEvBias,
                    )
                )
            }
        }

        return StudioScene(
            id = id,
            name = name,
            createdAtUtc = createdAtUtc,
            setupNotes = setupNotes,
            groupPresets = groupPresets,
            remoteChannel = remoteChannel,
            remoteOperatingMode = remoteMode,
            syncTriggerMode = syncMode,
            afAssistMode = afMode,
            rfPowerLevel = rfPower,
            readySignalMode = readySignal,
        )
    }

    fun decodeFromJson(jsonString: String): List<StudioScene> {
        val trimmed = jsonString.trim()
        if (trimmed.isEmpty()) {
            throw StudioSceneCodecException("Cannot decode empty JSON string")
        }

        try {
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                val list = mutableListOf<StudioScene>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    list.add(decodeScene(obj))
                }
                return list
            } else if (trimmed.startsWith("{")) {
                val obj = JSONObject(trimmed)
                if (obj.has(KEY_SCENES)) {
                    val arr = obj.getJSONArray(KEY_SCENES)
                    val list = mutableListOf<StudioScene>()
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        list.add(decodeScene(item))
                    }
                    return list
                } else if (obj.has(KEY_NAME) || obj.has(KEY_GROUP_PRESETS) || obj.has(KEY_ID)) {
                    return listOf(decodeScene(obj))
                } else {
                    throw StudioSceneCodecException("Unrecognized JSON object structure: missing '$KEY_SCENES' or scene properties")
                }
            } else {
                throw StudioSceneCodecException("Invalid JSON format: must start with '{' or '['")
            }
        } catch (e: JSONException) {
            throw StudioSceneCodecException("Failed to parse JSON: ${e.message}", e)
        }
    }
}
