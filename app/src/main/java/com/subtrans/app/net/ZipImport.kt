package com.subtrans.app.net

import com.subtrans.app.subtitle.SubtitleText
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Reading a subtitle pack.
 *
 * This is the route around the daily download limit. Plenty of sites hand out
 * a whole season — sometimes a whole series — as one archive, and one archive
 * costs one download instead of three hundred. Nothing here is written to
 * disk: entries are read straight into memory and handed to the parser, so an
 * archive with hostile paths inside it has nothing to attack.
 */
object ZipImport {

    /** Refuses a single entry larger than this; subtitles are never this big. */
    private const val MAX_ENTRY_BYTES = 8 * 1024 * 1024

    /** Stops runaway archives from exhausting memory. */
    private const val MAX_ENTRIES = 2_000

    private val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa", "sub")

    data class Extracted(
        val fileName: String,
        val text: String,
        /** Folder path inside the archive, sanitised; "" at the top level. */
        val relativeDir: String = "",
    )

    data class Result(
        val files: List<Extracted>,
        /** Entries skipped because they were not subtitles or were too large. */
        val skipped: Int,
    )

    fun extract(stream: InputStream): Result {
        val found = mutableListOf<Extracted>()
        var skipped = 0

        ZipInputStream(stream.buffered()).use { zip ->
            var seen = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                seen++
                if (seen > MAX_ENTRIES) break

                val whole = entry.name.replace('\\', '/')
                val name = whole.substringAfterLast('/')
                // The folder path is kept so the export can rebuild the same
                // shape, but stripped of anything that could escape a directory.
                val relativeDir = whole.substringBeforeLast('/', "")
                    .split('/')
                    .filter { it.isNotBlank() && it != "." && it != ".." }
                    .joinToString("/")
                val extension = name.substringAfterLast('.', "").lowercase()

                if (entry.isDirectory || name.isBlank() || extension !in SUBTITLE_EXTENSIONS) {
                    skipped++
                    zip.closeEntry()
                    continue
                }

                val bytes = zip.readBoundedBytes(MAX_ENTRY_BYTES)
                zip.closeEntry()

                if (bytes == null) {
                    skipped++
                    continue
                }
                found += Extracted(name, decode(bytes), relativeDir)
            }
        }

        return Result(found.sortedBy { it.relativeDir + "/" + it.fileName }, skipped)
    }

    private fun decode(bytes: ByteArray): String = SubtitleText.decode(bytes).text

    /** Reads at most [limit] bytes, returning null if the entry is larger. */
    private fun InputStream.readBoundedBytes(limit: Int): ByteArray? {
        val buffer = ByteArray(16 * 1024)
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val read = read(buffer)
            if (read == -1) break
            if (out.size() + read > limit) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
