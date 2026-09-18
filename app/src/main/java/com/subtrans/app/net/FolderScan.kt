package com.subtrans.app.net

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Walking a picked folder, however deeply it nests.
 *
 * Season packs arrive as a folder of folders — one per season, sometimes one
 * per episode — and picking three hundred files by hand is not a workflow.
 * The whole tree is walked instead, keeping each file's path relative to the
 * folder that was picked so the same shape can be rebuilt on the way out.
 *
 * DocumentFile.listFiles() is avoided deliberately: it issues a query per file
 * and crawls on a large tree. Querying the children collection directly is the
 * difference between seconds and minutes.
 */
object FolderScan {

    private val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa", "sub", "txt")

    /** A safety rail against picking a whole SD card by accident. */
    private const val MAX_FILES = 5_000
    private const val MAX_DEPTH = 8

    data class Found(
        val uri: Uri,
        val name: String,
        /** Directory path relative to the picked folder, "" at the top level. */
        val relativeDir: String,
    ) {
        val relativePath: String get() = if (relativeDir.isEmpty()) name else "$relativeDir/$name"
    }

    suspend fun scan(context: Context, treeUri: Uri): List<Found> = withContext(Dispatchers.IO) {
        val found = mutableListOf<Found>()
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return@withContext emptyList()

        walk(context, treeUri, rootId, "", 0, found)
        found.sortedBy { it.relativePath }
    }

    private fun walk(
        context: Context,
        treeUri: Uri,
        documentId: String,
        relativeDir: String,
        depth: Int,
        found: MutableList<Found>,
    ) {
        if (depth > MAX_DEPTH || found.size >= MAX_FILES) return

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )

        // Directories are collected first and recursed after the cursor closes,
        // so a deep tree never holds a cursor open per level.
        val directories = mutableListOf<Pair<String, String>>()

        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (found.size >= MAX_FILES) return@use
                val childId = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2)

                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    directories += childId to name
                    continue
                }
                if (name.substringAfterLast('.', "").lowercase() !in SUBTITLE_EXTENSIONS) continue

                found += Found(
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId),
                    name = name,
                    relativeDir = relativeDir,
                )
            }
        }

        for ((childId, name) in directories) {
            val nested = if (relativeDir.isEmpty()) name else "$relativeDir/$name"
            walk(context, treeUri, childId, nested, depth + 1, found)
        }
    }
}
