package com.subtrans.app.net

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writing the finished subtitles back out as one archive.
 *
 * A season that came in as a folder tree should leave as the same tree, so the
 * files land next to their videos without anyone rearranging them by hand. The
 * relative path each file arrived with is therefore kept all the way through
 * and written back into the archive.
 */
object ZipExport {

    data class Entry(
        /** Path inside the archive, directories included, e.g. "Season 01/ep01.srt". */
        val path: String,
        val text: String,
    )

    /**
     * Writes [entries] to [out]. Duplicate paths are made unique rather than
     * silently overwriting each other, because two folders can legitimately
     * hold a file of the same name.
     */
    fun write(out: OutputStream, entries: List<Entry>): Int {
        var written = 0
        val used = mutableSetOf<String>()

        ZipOutputStream(out.buffered()).use { zip ->
            for (entry in entries) {
                val path = uniquePath(sanitise(entry.path), used)
                used += path

                zip.putNextEntry(ZipEntry(path))
                zip.write(entry.text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                written++
            }
        }
        return written
    }

    /** Keeps the archive well-formed: no absolute paths, no parent traversal. */
    private fun sanitise(path: String): String {
        val cleaned = path.replace('\\', '/')
            .split('/')
            .filter { it.isNotBlank() && it != "." && it != ".." }
            .joinToString("/")
        return cleaned.ifEmpty { "subtitle.srt" }
    }

    private fun uniquePath(path: String, used: Set<String>): String {
        if (path !in used) return path

        val dot = path.lastIndexOf('.')
        val stem = if (dot == -1) path else path.substring(0, dot)
        val extension = if (dot == -1) "" else path.substring(dot)

        var n = 2
        while ("$stem ($n)$extension" in used) n++
        return "$stem ($n)$extension"
    }
}
