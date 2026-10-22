package dev.tizenarchive.companion.wifi

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.tizenarchive.companion.files.settings
import kotlinx.coroutines.flow.first

internal class WatchSettings(val mode: InstallMode = InstallMode.COMPANION, val address: String = "", val reinstall: Boolean = false)

internal interface WatchSettingsMemory {
    suspend fun load(): WatchSettings

    suspend fun save(settings: WatchSettings)
}

/** Remembers how packages are delivered and the address of the watch. */
internal class WatchSettingsStore(private val context: Context) : WatchSettingsMemory {
    override suspend fun load(): WatchSettings {
        val data = context.settings.data.first()
        val mode = InstallMode.entries.firstOrNull { it.name == data[MODE] } ?: InstallMode.COMPANION
        return WatchSettings(mode, data[ADDRESS].orEmpty(), data[REINSTALL] == "1")
    }

    override suspend fun save(settings: WatchSettings) {
        context.settings.edit {
            it[MODE] = settings.mode.name
            it[ADDRESS] = settings.address
            it[REINSTALL] = if (settings.reinstall) "1" else "0"
        }
    }

    private companion object {
        val MODE = stringPreferencesKey("install_mode")
        val ADDRESS = stringPreferencesKey("watch_address")
        val REINSTALL = stringPreferencesKey("watch_reinstall")
    }
}
