package app.prft.lightctrl.domain

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.prft.lightctrl.protocol.AirGroup
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private val Context.studioPreferencesDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "prft_studio_preferences",
)

@Singleton
class StudioPreferencesStore(
    private val context: Context?,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(context, Dispatchers.IO)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _groupRoles = MutableStateFlow<Map<AirGroup, String>>(emptyMap())
    val groupRoles: StateFlow<Map<AirGroup, String>> = _groupRoles.asStateFlow()

    private val _pinnedKitMacs = MutableStateFlow<Set<String>>(emptySet())
    val pinnedKitMacs: StateFlow<Set<String>> = _pinnedKitMacs.asStateFlow()

    private val _volumeKeyTestFlash = MutableStateFlow(false)
    val isVolumeKeyTestFlashEnabled: StateFlow<Boolean> = _volumeKeyTestFlash.asStateFlow()

    private val _autoReconnectPinned = MutableStateFlow(true)
    val isAutoReconnectPinnedEnabled: StateFlow<Boolean> = _autoReconnectPinned.asStateFlow()

    private val _dualPowerReadout = MutableStateFlow(false)
    val isDualPowerReadoutEnabled: StateFlow<Boolean> = _dualPowerReadout.asStateFlow()

    private val _savedScenes = MutableStateFlow<List<StudioScene>>(emptyList())
    val savedScenes: StateFlow<List<StudioScene>> = _savedScenes.asStateFlow()

    init {
        if (context != null) {
            scope.launch {
                context.studioPreferencesDataStore.data.collect { prefs ->
                    val roles = buildMap {
                        for (grp in AirGroup.entries) {
                            if (grp == AirGroup.ALL) continue
                            val key = stringPreferencesKey(KEY_ROLE_PREFIX + grp.name)
                            val v = prefs[key]
                            if (!v.isNullOrBlank()) put(grp, v.trim())
                        }
                    }
                    _groupRoles.value = roles

                    val pinned = prefs[KEY_PINNED_MACS] ?: emptySet()
                    _pinnedKitMacs.value = pinned.map { normalizeMac(it) }.toSet()

                    _volumeKeyTestFlash.value = prefs[KEY_VOLUME_KEY_FLASH] ?: false
                    _autoReconnectPinned.value = prefs[KEY_AUTO_RECONNECT] ?: true
                    _dualPowerReadout.value = prefs[KEY_DUAL_POWER] ?: false

                    val rawScenes = prefs[KEY_SAVED_SCENES]
                    if (!rawScenes.isNullOrBlank()) {
                        try {
                            _savedScenes.value = StudioSceneJsonCodec.decodeFromJson(rawScenes)
                        } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    fun getGroupRoles(): Flow<Map<AirGroup, String>> = groupRoles

    suspend fun setGroupRole(group: AirGroup, role: String?) {
        if (group == AirGroup.ALL) return
        val cleaned = role?.trim()?.takeIf { it.isNotEmpty() }
        _groupRoles.update { cur ->
            if (cleaned == null) cur - group else cur + (group to cleaned)
        }
        val ctx = context ?: return
        val prefKey = stringPreferencesKey(KEY_ROLE_PREFIX + group.name)
        ctx.studioPreferencesDataStore.edit { prefs ->
            if (cleaned == null) prefs.remove(prefKey) else prefs[prefKey] = cleaned
        }
    }

    fun getPinnedKitMacs(): Flow<Set<String>> = pinnedKitMacs

    suspend fun setDevicePinned(macAddress: String, pinned: Boolean) {
        val norm = normalizeMac(macAddress)
        if (norm.isEmpty()) return
        _pinnedKitMacs.update { cur ->
            if (pinned) cur + norm else cur - norm
        }
        val ctx = context ?: return
        ctx.studioPreferencesDataStore.edit { prefs ->
            val cur = prefs[KEY_PINNED_MACS] ?: emptySet()
            val next = if (pinned) cur + norm else cur - norm
            prefs[KEY_PINNED_MACS] = next
        }
    }

    fun isVolumeKeyTestFlashEnabled(): Flow<Boolean> = isVolumeKeyTestFlashEnabled

    suspend fun setVolumeKeyTestFlashEnabled(enabled: Boolean) {
        _volumeKeyTestFlash.value = enabled
        val ctx = context ?: return
        ctx.studioPreferencesDataStore.edit { prefs ->
            prefs[KEY_VOLUME_KEY_FLASH] = enabled
        }
    }

    fun isAutoReconnectPinnedEnabled(): Flow<Boolean> = isAutoReconnectPinnedEnabled

    suspend fun setAutoReconnectPinnedEnabled(enabled: Boolean) {
        _autoReconnectPinned.value = enabled
        val ctx = context ?: return
        ctx.studioPreferencesDataStore.edit { prefs ->
            prefs[KEY_AUTO_RECONNECT] = enabled
        }
    }

    fun isDualPowerReadoutEnabled(): Flow<Boolean> = isDualPowerReadoutEnabled

    suspend fun setDualPowerReadoutEnabled(enabled: Boolean) {
        _dualPowerReadout.value = enabled
        val ctx = context ?: return
        ctx.studioPreferencesDataStore.edit { prefs ->
            prefs[KEY_DUAL_POWER] = enabled
        }
    }

    fun getSavedScenes(): Flow<List<StudioScene>> = savedScenes

    suspend fun saveScene(scene: StudioScene) {
        _savedScenes.update { cur ->
            val filtered = cur.filterNot { it.id == scene.id }
            listOf(scene) + filtered
        }
        val ctx = context ?: return
        ctx.studioPreferencesDataStore.edit { prefs ->
            prefs[KEY_SAVED_SCENES] = StudioSceneJsonCodec.encodeToJson(_savedScenes.value)
        }
    }

    suspend fun deleteScene(sceneId: String) {
        _savedScenes.update { cur -> cur.filterNot { it.id == sceneId } }
        val ctx = context ?: return
        ctx.studioPreferencesDataStore.edit { prefs ->
            prefs[KEY_SAVED_SCENES] = StudioSceneJsonCodec.encodeToJson(_savedScenes.value)
        }
    }

    suspend fun importScenes(scenes: List<StudioScene>) {
        if (scenes.isEmpty()) return
        _savedScenes.update { cur ->
            val existingIds = cur.map { it.id }.toSet()
            val newUnique = scenes.filterNot { it.id in existingIds }
            cur + newUnique
        }
        val ctx = context ?: return
        ctx.studioPreferencesDataStore.edit { prefs ->
            prefs[KEY_SAVED_SCENES] = StudioSceneJsonCodec.encodeToJson(_savedScenes.value)
        }
    }

    companion object {
        private const val KEY_ROLE_PREFIX = "role_group_"
        private val KEY_PINNED_MACS = stringSetPreferencesKey("pinned_kit_macs")
        private val KEY_VOLUME_KEY_FLASH = booleanPreferencesKey("toggle_volume_key_flash")
        private val KEY_AUTO_RECONNECT = booleanPreferencesKey("toggle_auto_reconnect_pinned")
        private val KEY_DUAL_POWER = booleanPreferencesKey("toggle_dual_power_readout")
        private val KEY_SAVED_SCENES = stringPreferencesKey("saved_scenes_json")

        fun normalizeMac(address: String): String =
            address.trim().uppercase(Locale.US)
    }
}
