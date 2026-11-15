package com.sf12.rr3tool

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.Log
import java.util.ArrayDeque

internal data class GameDataFile(
    val name: String,
    val relativePath: String,
    val uri: Uri,
    val sizeBytes: Long
)

internal object GameDataBrowser {
    private data class Folder(
        val documentId: String,
        val path: String,
        val depth: Int
    )

    private data class Child(
        val documentId: String,
        val name: String,
        val isDirectory: Boolean,
        val sizeBytes: Long
    )

    private const val MAX_FILES = 100_000
    private const val MAX_FOLDERS_TO_FIND_DEPOT = 500
    private const val MAX_DEPOT_DEPTH = 6

    private val excludedExtensions = setOf(
        "sba", "pvr", "dds", "astc", "ktx", "m3g", "sb3d",
        "png", "jpg", "jpeg", "bmp", "tga", "points", "banim"
    )

    fun scan(resolver: ContentResolver, treeUri: Uri): List<GameDataFile> {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootName = rootId.substringAfterLast('/').substringAfterLast(':')
        val depot = if (rootName.equals(".depot", ignoreCase = true)) {
            Folder(rootId, "", 0)
        } else {
            findDepotFolder(resolver, treeUri, rootId)
                ?: throw IllegalStateException(
                    "No .depot folder was found below the selected folder. Select the RR3 files/.depot folder directly."
                )
        }

        val result = ArrayList<GameDataFile>()
        val queue = ArrayDeque<Folder>()
        val visitedFolders = HashSet<String>()
        queue.add(depot)

        while (queue.isNotEmpty()) {
            val folder = queue.removeFirst()
            if (!visitedFolders.add(folder.documentId)) continue

            for (child in children(resolver, treeUri, folder.documentId)) {
                val path = if (folder.path.isEmpty()) child.name else folder.path + "/" + child.name
                if (child.isDirectory) {
                    if (folder.depth < 128) {
                        queue.add(Folder(child.documentId, path, folder.depth + 1))
                    }
                    continue
                }

                val effectiveName = child.name.removeSuffixIgnoreCase(".z")
                    .removeSuffixIgnoreCase(".bin")
                val extension = effectiveName.substringAfterLast('.', "").lowercase()
                if (extension in excludedExtensions) continue

                result.add(
                    GameDataFile(
                        name = child.name,
                        relativePath = path,
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, child.documentId),
                        sizeBytes = child.sizeBytes
                    )
                )
                if (result.size >= MAX_FILES) {
                    throw IllegalStateException(
                        "The RR3 folder contains more than $MAX_FILES supported files. Narrow the selected folder to files/.depot."
                    )
                }
            }
        }

        return result.sortedBy { it.relativePath.lowercase() }
    }

    private fun findDepotFolder(
        resolver: ContentResolver,
        treeUri: Uri,
        rootId: String
    ): Folder? {
        val queue = ArrayDeque<Folder>()
        val visited = HashSet<String>()
        queue.add(Folder(rootId, "", 0))
        var inspected = 0

        while (queue.isNotEmpty() && inspected < MAX_FOLDERS_TO_FIND_DEPOT) {
            val folder = queue.removeFirst()
            if (!visited.add(folder.documentId)) continue
            inspected++

            for (child in children(resolver, treeUri, folder.documentId)) {
                if (!child.isDirectory) continue
                val path = if (folder.path.isEmpty()) child.name else folder.path + "/" + child.name
                if (child.name.equals(".depot", ignoreCase = true)) {
                    return Folder(child.documentId, "", 0)
                }
                if (folder.depth < MAX_DEPOT_DEPTH) {
                    queue.add(Folder(child.documentId, path, folder.depth + 1))
                }
            }
        }
        return null
    }

    private fun children(
        resolver: ContentResolver,
        treeUri: Uri,
        parentDocumentId: String
    ): List<Child> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            parentDocumentId
        )
        val projection = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE
        )
        val cursor = resolver.query(childrenUri, projection, null, null, null)
            ?: throw IllegalStateException("The selected storage provider did not return a folder listing.")

        return cursor.use {
            val idCol = it.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
            val nameCol = it.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
            val mimeCol = it.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)
            val sizeCol = it.getColumnIndex(Document.COLUMN_SIZE)
            val output = ArrayList<Child>()
            while (it.moveToNext()) {
                val id = it.getString(idCol) ?: continue
                val name = it.getString(nameCol) ?: continue
                val mime = it.getString(mimeCol)
                val size = if (sizeCol >= 0 && !it.isNull(sizeCol)) it.getLong(sizeCol) else -1L
                output.add(Child(id, name, mime == Document.MIME_TYPE_DIR, size))
            }
            output
        }
    }

    private fun String.removeSuffixIgnoreCase(suffix: String): String =
        if (endsWith(suffix, ignoreCase = true)) dropLast(suffix.length) else this
}
