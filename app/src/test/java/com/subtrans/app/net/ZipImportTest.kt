package com.subtrans.app.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipImportTest {

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArrayInputStream {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return ByteArrayInputStream(out.toByteArray())
    }

    private val srt = "1\n00:00:01,000 --> 00:00:02,000\nHello\n".toByteArray()

    @Test
    fun `pulls every subtitle out of the archive`() {
        val result = ZipImport.extract(
            zipOf("ep01.srt" to srt, "ep02.srt" to srt, "ep03.ass" to srt),
        )
        assertEquals(3, result.files.size)
        assertEquals(0, result.skipped)
    }

    @Test
    fun `ignores everything that is not a subtitle`() {
        val result = ZipImport.extract(
            zipOf(
                "ep01.srt" to srt,
                "cover.jpg" to byteArrayOf(1, 2, 3),
                "readme.txt" to "hi".toByteArray(),
            ),
        )
        assertEquals(1, result.files.size)
        assertEquals(2, result.skipped)
    }

    @Test
    fun `flattens folders inside the archive`() {
        val result = ZipImport.extract(
            zipOf("Boruto/Season 01/ep01.srt" to srt),
        )
        assertEquals(1, result.files.size)
        // The folder structure is dropped; only the file name survives.
        assertEquals("ep01.srt", result.files[0].fileName)
    }

    @Test
    fun `a path trying to escape the archive cannot, because nothing is written`() {
        val result = ZipImport.extract(zipOf("../../etc/passwd.srt" to srt))
        assertEquals(1, result.files.size)
        assertEquals("passwd.srt", result.files[0].fileName)
        assertTrue(!result.files[0].fileName.contains(".."))
        assertTrue(!result.files[0].fileName.contains("/"))
    }

    @Test
    fun `skips an entry that is implausibly large for a subtitle`() {
        val huge = ByteArray(9 * 1024 * 1024) { 'a'.code.toByte() }
        val result = ZipImport.extract(zipOf("ep01.srt" to srt, "bomb.srt" to huge))
        assertEquals(1, result.files.size)
        assertEquals(1, result.skipped)
    }

    @Test
    fun `reads the text back intact`() {
        val bengali = "1\n00:00:01,000 --> 00:00:02,000\nসকাল হয়ে গেল?\n".toByteArray(Charsets.UTF_8)
        val result = ZipImport.extract(zipOf("ep01.srt" to bengali))
        assertTrue(result.files[0].text.contains("সকাল হয়ে গেল?"))
    }

    @Test
    fun `falls back to windows-1252 when the bytes are not utf-8`() {
        // A Latin-1 accented line that is invalid as UTF-8.
        val latin1 = "1\r\n00:00:01,000 --> 00:00:02,000\r\nCafé fermé\r\n"
            .toByteArray(charset("windows-1252"))
        val result = ZipImport.extract(zipOf("ep01.srt" to latin1))
        assertTrue(result.files[0].text, result.files[0].text.contains("Café fermé"))
    }

    @Test
    fun `an archive with nothing useful comes back empty rather than failing`() {
        val result = ZipImport.extract(zipOf("notes.md" to "hi".toByteArray()))
        assertTrue(result.files.isEmpty())
        assertEquals(1, result.skipped)
    }

    @Test
    fun `returns files in a predictable order`() {
        val result = ZipImport.extract(
            zipOf("ep03.srt" to srt, "ep01.srt" to srt, "ep02.srt" to srt),
        )
        assertEquals(listOf("ep01.srt", "ep02.srt", "ep03.srt"), result.files.map { it.fileName })
    }
}

class BestPerEpisodeTest {

    private fun entry(
        episode: Int,
        downloads: Int,
        trusted: Boolean = false,
        hearingImpaired: Boolean = false,
        fileId: Int = episode * 100 + downloads,
    ) = SourceEntry(
        id = fileId.toString(),
        fileName = "ep$episode.srt",
        release = "",
        season = 1,
        episode = episode,
        episodeTitle = null,
        downloads = downloads,
        hearingImpaired = hearingImpaired,
        trusted = trusted,
    )

    @Test
    fun `keeps the most downloaded upload for each episode`() {
        val best = bestPerEpisode(listOf(entry(1, 10), entry(1, 900), entry(2, 5)))
        assertEquals(900, best[1]?.downloads)
        assertEquals(5, best[2]?.downloads)
        assertEquals(2, best.size)
    }

    @Test
    fun `a trusted upload wins even with fewer downloads`() {
        val best = bestPerEpisode(listOf(entry(1, 5000), entry(1, 10, trusted = true)))
        assertTrue(best[1]!!.trusted)
    }

    @Test
    fun `prefers a normal upload over a hearing-impaired one when close`() {
        val best = bestPerEpisode(listOf(entry(1, 100, hearingImpaired = true), entry(1, 100)))
        assertTrue(!best[1]!!.hearingImpaired)
    }

    @Test
    fun `drops entries with no episode number`() {
        val loose = entry(1, 10).copy(episode = null)
        assertTrue(bestPerEpisode(listOf(loose)).isEmpty())
    }
}
