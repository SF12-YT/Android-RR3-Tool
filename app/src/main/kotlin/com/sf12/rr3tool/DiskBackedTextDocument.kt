package com.sf12.rr3tool

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

internal data class TextSearchHit(
    val pageIndex: Int,
    val start: Int,
    val end: Int,
    val lineNumber: Long
)

/**
 * A disk-backed, random-access text document.
 *
 * Only a small line-start index is kept in memory. Source pages are read on
 * demand, and edited pages are stored as separate small files in the cache.
 * This lets a recycled list render a large document without retaining every
 * page's String or EditText at once.
 */
internal class DiskBackedTextDocument(
    private val sourceFile: File,
    private val pageSize: Int = 80
) : Closeable {
    private val pageStarts = ArrayList<Long>()
    private val pageLineCounts = ArrayList<Int>()
    private val editDirectory = File(sourceFile.parentFile, sourceFile.name + ".edits")
    private val sourceLength = sourceFile.length()
    private var nextSequentialPage = 0
    private var closed = false

    val pageCount: Int
        get() = pageStarts.size

    init {
        require(pageSize in 16..512) { "Text page size must be between 16 and 512 lines." }
        require(sourceFile.isFile) { "The source text file does not exist." }
        if (!editDirectory.isDirectory && !editDirectory.mkdirs()) {
            throw IllegalStateException("Could not create the temporary text-editor workspace.")
        }
        buildPageIndex()
    }

    private fun buildPageIndex() {
        pageStarts.clear()
        pageLineCounts.clear()
        pageStarts.add(0L)

        var bytesSeen = 0L
        var newlineCount = 0L
        var lastByte = -1
        val buffer = ByteArray(64 * 1024)

        BufferedInputStream(FileInputStream(sourceFile), buffer.size).use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                for (index in 0 until count) {
                    lastByte = buffer[index].toInt() and 0xFF
                    bytesSeen++
                    if (lastByte == 0x0A) {
                        newlineCount++
                        if (newlineCount % pageSize == 0L && bytesSeen < sourceLength) {
                            pageStarts.add(bytesSeen)
                        }
                    }
                }
            }
        }

        val totalLines = newlineCount + if (bytesSeen > 0L && lastByte != 0x0A) 1L else 0L
        val requiredPages = if (totalLines == 0L) {
            1
        } else {
            ((totalLines + pageSize - 1L) / pageSize).toInt()
        }

        while (pageStarts.size < requiredPages) {
            pageStarts.add(sourceLength)
        }
        while (pageStarts.size > requiredPages) {
            pageStarts.removeAt(pageStarts.lastIndex)
        }

        repeat(requiredPages) { page ->
            val remaining = (totalLines - page.toLong() * pageSize).coerceAtLeast(0L)
            pageLineCounts.add(remaining.coerceAtMost(pageSize.toLong()).toInt())
        }
    }

    @Synchronized
    fun readNextPage(): String? {
        checkOpen()
        if (nextSequentialPage >= pageCount) return null
        return readPage(nextSequentialPage++)
    }

    @Synchronized
    fun readPage(pageIndex: Int): String {
        checkOpen()
        require(pageIndex in 0 until pageCount) { "Text page index is outside the document." }

        val edited = editedPageFile(pageIndex)
        if (edited.isFile) {
            return normalizeLineEndings(edited.readText(StandardCharsets.UTF_8))
        }

        val start = pageStarts[pageIndex]
        val end = if (pageIndex + 1 < pageStarts.size) pageStarts[pageIndex + 1] else sourceLength
        val length = (end - start).coerceAtLeast(0L)
        require(length <= MAX_PAGE_BYTES) {
            "A single text page is too large to display safely (" + length + " bytes)."
        }
        if (length == 0L) return ""

        val bytes = ByteArray(length.toInt())
        RandomAccessFile(sourceFile, "r").use { file ->
            file.seek(start)
            file.readFully(bytes)
        }
        return normalizeLineEndings(String(bytes, StandardCharsets.UTF_8))
    }

    @Synchronized
    fun saveEditedPage(pageIndex: Int, text: String) {
        checkOpen()
        require(pageIndex in 0 until pageCount) { "Text page index is outside the document." }

        val destination = editedPageFile(pageIndex)
        val temporary = File(editDirectory, "page_" + pageIndex + ".tmp")
        val textWithOriginalLineEndings = restoreOriginalLineEndings(pageIndex, text)
        FileOutputStream(temporary).buffered().use { output ->
            output.write(textWithOriginalLineEndings.toByteArray(StandardCharsets.UTF_8))
            output.flush()
        }
        if (destination.exists() && !destination.delete()) {
            temporary.delete()
            throw IllegalStateException("Could not replace edited text page " + pageIndex + ".")
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            throw IllegalStateException("Could not save edited text page " + pageIndex + ".")
        }
    }

    /**
     * Searches the entire document, not merely pages currently attached to the
     * screen. Edited page overlays take precedence over the untouched source.
     * The caller supplies the current page/offset to implement Next and Previous.
     */
    @Synchronized
    fun findMatch(
        query: String,
        startPage: Int,
        startOffset: Int,
        forward: Boolean
    ): TextSearchHit? {
        checkOpen()
        if (query.isEmpty() || pageCount == 0) return null

        val firstPage = startPage.coerceIn(0, pageCount - 1)
        for (step in 0 until pageCount) {
            val pageIndex = if (forward) {
                (firstPage + step) % pageCount
            } else {
                (firstPage - step + pageCount) % pageCount
            }
            val page = readPage(pageIndex)
            val found = if (forward) {
                val from = if (step == 0) startOffset.coerceIn(0, page.length) else 0
                page.indexOf(query, from, ignoreCase = true)
            } else {
                val from = if (step == 0) {
                    startOffset.coerceIn(-1, page.length)
                } else {
                    page.length
                }
                if (from < 0) -1 else page.lastIndexOf(query, from, ignoreCase = true)
            }

            if (found >= 0) {
                val lineOffset = page.substring(0, found).count { it == '\n' }.toLong()
                return TextSearchHit(
                    pageIndex = pageIndex,
                    start = found,
                    end = found + query.length,
                    lineNumber = pageIndex.toLong() * pageSize + lineOffset + 1L
                )
            }
        }
        return null
    }

    /**
     * Streams untouched source ranges and edited page overlays into a new file.
     * The original source is never modified.
     */
    @Synchronized
    fun writeMerged(outputFile: File, preserveLineCount: Boolean = false) {
        checkOpen()
        outputFile.parentFile?.let { parent ->
            if (!parent.isDirectory && !parent.mkdirs()) {
                throw IllegalStateException("Could not create the output directory.")
            }
        }

        BufferedOutputStream(FileOutputStream(outputFile), COPY_BUFFER_SIZE).use { output ->
            RandomAccessFile(sourceFile, "r").use { source ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                for (pageIndex in 0 until pageCount) {
                    val edited = editedPageFile(pageIndex)
                    if (edited.isFile) {
                        if (preserveLineCount) {
                            val text = edited.readText(StandardCharsets.UTF_8)
                            val actualLines = countLines(text)
                            val expectedLines = pageLineCounts[pageIndex]
                            if (actualLines != expectedLines) {
                                throw IllegalStateException(
                                    "RR3 game text must keep its original line count. Page " +
                                        (pageIndex + 1) + " must contain exactly " + expectedLines + " line(s)."
                                )
                            }
                        }
                        FileInputStream(edited).use { input ->
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                            }
                        }
                    } else {
                        val start = pageStarts[pageIndex]
                        val end = if (pageIndex + 1 < pageStarts.size) {
                            pageStarts[pageIndex + 1]
                        } else {
                            sourceLength
                        }
                        var remaining = (end - start).coerceAtLeast(0L)
                        source.seek(start)
                        while (remaining > 0L) {
                            val wanted = minOf(buffer.size.toLong(), remaining).toInt()
                            val read = source.read(buffer, 0, wanted)
                            if (read < 0) throw IllegalStateException("The original text file changed while it was being edited.")
                            output.write(buffer, 0, read)
                            remaining -= read.toLong()
                        }
                    }
                }
            }
            output.flush()
        }
    }

    @Synchronized
    fun deleteEditedPages() {
        editDirectory.deleteRecursively()
    }

    /**
     * The editor displays all line endings as '\n', but RR3 language files can
     * use CRLF. Reapply the source page's exact newline sequence when saving;
     * otherwise one edited page can introduce mixed line endings into the
     * encrypted game-text file.
     */
    private fun restoreOriginalLineEndings(pageIndex: Int, text: String): String {
        val start = pageStarts[pageIndex]
        val end = if (pageIndex + 1 < pageStarts.size) pageStarts[pageIndex + 1] else sourceLength
        val length = (end - start).coerceAtLeast(0L)
        if (length == 0L) return text
        require(length <= MAX_PAGE_BYTES) {
            "A single text page is too large to save safely (" + length + " bytes)."
        }

        val sourceBytes = ByteArray(length.toInt())
        RandomAccessFile(sourceFile, "r").use { file ->
            file.seek(start)
            file.readFully(sourceBytes)
        }

        val endings = ArrayList<String>()
        var index = 0
        while (index < sourceBytes.size) {
            when (sourceBytes[index].toInt() and 0xFF) {
                0x0D -> {
                    if (index + 1 < sourceBytes.size &&
                        (sourceBytes[index + 1].toInt() and 0xFF) == 0x0A
                    ) {
                        endings.add("\r\n")
                        index += 2
                    } else {
                        endings.add("\r")
                        index++
                    }
                }
                0x0A -> {
                    endings.add("\n")
                    index++
                }
                else -> index++
            }
        }
        if (endings.isEmpty()) return text

        val fallback = endings.first()
        var newlineIndex = 0
        return buildString(text.length + endings.count { it.length > 1 }) {
            for (character in text) {
                if (character == '\n') {
                    append(endings.getOrNull(newlineIndex) ?: fallback)
                    newlineIndex++
                } else {
                    append(character)
                }
            }
        }
    }

    private fun normalizeLineEndings(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n')

    private fun editedPageFile(pageIndex: Int): File =
        File(editDirectory, "page_" + pageIndex + ".txt")

    private fun countLines(text: String): Int {
        if (text.isEmpty()) return 0
        val newlineCount = text.count { it == '\n' }
        return newlineCount + if (text.endsWith('\n')) 0 else 1
    }

    private fun checkOpen() {
        check(!closed) { "The text editor is already closed." }
    }

    @Synchronized
    override fun close() {
        closed = true
    }

    companion object {
        private const val MAX_PAGE_BYTES = 32L * 1024L * 1024L
        private const val COPY_BUFFER_SIZE = 64 * 1024
    }
}
