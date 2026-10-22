package dev.tizenarchive.companion.wifi

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.tizenarchive.companion.files.settings
import kotlinx.coroutines.flow.first

/** A package this app installed on the watch. The watch's own list cannot tell these from system packages, so the app keeps track. */
internal class RememberedPackage(val id: String, val version: String?, val name: String?, val installedAt: Long)

internal interface WatchHistoryMemory {
    suspend fun load(): List<RememberedPackage>

    suspend fun record(item: RememberedPackage)

    suspend fun forget(packageId: String)
}

/** One line per package, tab-separated, newest last; only the latest record of a package is kept. */
internal class WatchHistoryStore(private val context: Context) : WatchHistoryMemory {
    override suspend fun load(): List<RememberedPackage> = decode(context.settings.data.first()[HISTORY].orEmpty())

    override suspend fun record(item: RememberedPackage) = update { list -> list.filterNot { it.id == item.id } + item }

    override suspend fun forget(packageId: String) = update { list -> list.filterNot { it.id == packageId } }

    private suspend fun update(change: (List<RememberedPackage>) -> List<RememberedPackage>) {
        context.settings.edit { prefs ->
            prefs[HISTORY] = encode(change(decode(prefs[HISTORY].orEmpty())).takeLast(MAX_ITEMS))
        }
    }

    private companion object {
        val HISTORY = stringPreferencesKey("watch_history")
        const val MAX_ITEMS = 500

        fun encode(items: List<RememberedPackage>): String = HistoryCodec.encode(items)

        fun decode(text: String): List<RememberedPackage> = HistoryCodec.decode(text)
    }
}

internal object HistoryCodec {
    private val SEPARATORS = Regex("[\\t\\r\\n]")

    fun encode(items: List<RememberedPackage>): String = items.joinToString("\n") {
        listOf(it.id, it.version.orEmpty(), it.name.orEmpty(), it.installedAt.toString())
            .joinToString("\t") { field -> field.replace(SEPARATORS, " ") }
    }

    fun decode(text: String): List<RememberedPackage> = text.lineSequence().mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size != 4 || parts[0].isEmpty()) return@mapNotNull null
        RememberedPackage(parts[0], parts[1].ifEmpty { null }, parts[2].ifEmpty { null }, parts[3].toLongOrNull() ?: 0L)
    }.toList()
}
