package dev.tizenarchive.companion.files

import android.content.Context
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private val Context.settings by preferencesDataStore(name = "settings")

/** Remembers the chosen folder (a persisted SAF tree URI). */
internal class FolderStore(private val context: Context) {
    suspend fun load(): Uri? = context.settings.data.first()[FOLDER]?.let(Uri::parse)

    suspend fun save(uri: Uri) {
        context.settings.edit { it[FOLDER] = uri.toString() }
    }

    private companion object {
        val FOLDER = stringPreferencesKey("folder_uri")
    }
}
