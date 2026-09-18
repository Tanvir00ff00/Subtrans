package com.subtrans.app.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileLabelTest {

    /** The shape that exposed the problem: 293 names identical for 40 characters. */
    private fun realName(episode: Int) =
        "Boruto_Naruto_Next_Generations_[English]_S1_E${episode}_English.srt"

    /* ----------------------------------------------------- episode parsing */

    @Test
    fun `reads season and episode from an underscore release name`() {
        assertEquals(FileLabel.Episode(1, 275), FileLabel.episodeOf(realName(275)))
    }

    @Test
    fun `reads the common S01E05 form`() {
        assertEquals(FileLabel.Episode(1, 5), FileLabel.episodeOf("Show.S01E05.1080p.srt"))
    }

    @Test
    fun `reads the 1x05 form`() {
        assertEquals(FileLabel.Episode(1, 5), FileLabel.episodeOf("Show 1x05.srt"))
    }

    @Test
    fun `reads a dashed episode number with no season`() {
        assertEquals(FileLabel.Episode(null, 42), FileLabel.episodeOf("[Group] Boruto - 042.srt"))
    }

    @Test
    fun `reads Ep 12 written out`() {
        assertEquals(FileLabel.Episode(null, 12), FileLabel.episodeOf("Series Ep. 12.srt"))
    }

    @Test
    fun `a year is not mistaken for an episode`() {
        assertNull(FileLabel.episodeOf("Some Movie (2019).srt"))
    }

    @Test
    fun `a file with no episode at all returns nothing`() {
        assertNull(FileLabel.episodeOf("interview.srt"))
    }

    /* ------------------------------------------------------------ heading */

    @Test
    fun `the heading carries what actually tells the files apart`() {
        assertEquals("S1 · E275", FileLabel.heading(realName(275)))
        assertEquals("S1 · E1", FileLabel.heading(realName(1)))
    }

    @Test
    fun `headings differ for every episode of a series`() {
        val headings = (1..293).map { FileLabel.heading(realName(it)) }
        // The bug being guarded against: 293 rows that all read the same.
        assertEquals(293, headings.distinct().size)
    }

    @Test
    fun `a name with no episode falls back to the name itself`() {
        assertEquals("interview", FileLabel.heading("interview.srt"))
    }

    /* ---------------------------------------------------- middle ellipsis */

    @Test
    fun `shortening keeps the end, because the end is the useful part`() {
        val short = FileLabel.middleEllipsis(realName(275), 32)
        assertTrue(short, short.length <= 32)
        assertTrue("episode lost: $short", short.contains("275"))
        assertTrue(short.startsWith("Boruto"))
        assertTrue(short.contains("…"))
    }

    @Test
    fun `two adjacent episodes stay distinguishable when shortened`() {
        val a = FileLabel.middleEllipsis(realName(274), 30)
        val b = FileLabel.middleEllipsis(realName(275), 30)
        assertTrue("$a == $b", a != b)
    }

    @Test
    fun `a short name is left alone`() {
        assertEquals("ep01.srt", FileLabel.middleEllipsis("ep01.srt", 32))
    }

    /* ------------------------------------------------------ natural order */

    @Test
    fun `episode two sorts before episode ten`() {
        val names = listOf(realName(10), realName(2), realName(1))
        val sorted = FileLabel.sortNaturally(names) { it }
        assertEquals(listOf(realName(1), realName(2), realName(10)), sorted)
    }

    @Test
    fun `a whole season lands in reading order`() {
        val shuffled = (1..293).map { realName(it) }.shuffled()
        val sorted = FileLabel.sortNaturally(shuffled) { it }
        assertEquals((1..293).map { realName(it) }, sorted)
    }

    @Test
    fun `sorting keeps every item`() {
        val names = listOf("b.srt", "a.srt", "c.srt")
        assertEquals(names.size, FileLabel.sortNaturally(names) { it }.size)
    }
}
