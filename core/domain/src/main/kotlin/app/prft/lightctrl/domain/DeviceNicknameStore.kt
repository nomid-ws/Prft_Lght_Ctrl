package app.prft.lightctrl.domain

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

private val Context.nicknameDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "prft_equipment_nicknames",
)

/**
 * Persistent studio nickname repository keyed by normalized BLE MAC address.
 * Falls back to an in-memory map during JVM unit tests where [context] is null.
 */
class DeviceNicknameStore(
    private val context: Context?,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _nicknames = MutableStateFlow<Map<String, String>>(emptyMap())
    val nicknames: StateFlow<Map<String, String>> = _nicknames.asStateFlow()

    init {
        if (context != null) {
            scope.launch {
                context.nicknameDataStore.data.collect { prefs ->
                    val mapped = buildMap {
                        for ((key, value) in prefs.asMap()) {
                            if (key.name.startsWith(KEY_PREFIX) && value is String && value.isNotBlank()) {
                                val mac = key.name.removePrefix(KEY_PREFIX)
                                put(mac, value)
                            }
                        }
                    }
                    _nicknames.value = mapped
                }
            }
        }
    }

    fun setNickname(address: String, rawNickname: String?) {
        val norm = normalizeAddress(address)
        if (norm.isEmpty()) return
        val cleaned = rawNickname?.trim()?.takeIf { it.isNotEmpty() }

        // Immediate 0ms optimistic state update
        _nicknames.update { cur ->
            if (cleaned == null) cur - norm else cur + (norm to cleaned)
        }

        val ctx = context ?: return
        scope.launch {
            val prefKey = stringPreferencesKey(KEY_PREFIX + norm)
            ctx.nicknameDataStore.edit { mutablePrefs ->
                if (cleaned == null) {
                    mutablePrefs.remove(prefKey)
                } else {
                    mutablePrefs[prefKey] = cleaned
                }
            }
        }
    }

    companion object {
        private const val KEY_PREFIX = "nick_"

        fun normalizeAddress(address: String): String =
            address.trim().uppercase(Locale.US)
    }
}
