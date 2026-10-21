package dev.tizenarchive.companion.files

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.util.Locale

internal data class PackageFile(val uri: Uri, val name: String, val size: Long)

/** Lists packages in a chosen folder; [ContentFolderSource] reads the real one. */
internal interface FolderSource {
    fun displayName(tree: Uri): String?

    fun scan(tree: Uri): List<PackageFile>
}

/** Walks a Storage Access Framework tree and lists `.wgt` and `.tpk` files. */
internal object FolderScanner {
    private const val MAX_DEPTH = 12
    private const val MAX_FILES = 10_000

    fun scan(resolver: ContentResolver, tree: Uri): List<PackageFile> {
        val found = mutableListOf<PackageFile>()
        val pending = ArrayDeque<Pair<String, Int>>()
        pending.add(DocumentsContract.getTreeDocumentId(tree) to 0)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        while (pending.isNotEmpty() && found.size < MAX_FILES) {
            val (parentId, depth) = pending.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            resolver.query(children, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext() && found.size < MAX_FILES) {
                    val id = cursor.getString(0)
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2)
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (depth < MAX_DEPTH) pending.add(id to depth + 1)
                    } else if (isTizenPackage(name)) {
                        found += PackageFile(DocumentsContract.buildDocumentUriUsingTree(tree, id), name, cursor.getLong(3))
                    }
                }
            }
        }
        return found.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    fun displayName(resolver: ContentResolver, tree: Uri): String? {
        val document = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return resolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    private fun isTizenPackage(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.endsWith(".wgt") || lower.endsWith(".tpk")
    }
}
