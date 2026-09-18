package com.subtrans.app.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimingTest {

    private val srt = """
        1
        00:00:01,000 --> 00:00:03,480
        First line

        2
        00:01:59,800 --> 00:02:01,000
        Second line
    """.trimIndent()

    @Test
    fun `moves every timestamp forward by the same amount`() {
        val out = serialize(shifted(parse("a.srt", srt), 1_500))
        assertTrue(out.contains("00:00:02,500 --> 00:00:04,980"))
        assertTrue(out.contains("00:02:01,300 --> 00:02:02,500"))
    }

    @Test
    fun `moves timestamps backward`() {
        val out = serialize(shifted(parse("a.srt", srt), -800))
        assertTrue(out.contains("00:00:00,200 --> 00:00:02,680"))
    }

    @Test
    fun `carries across the minute boundary`() {
        val out = serialize(shifted(parse("a.srt", srt), 500))
        assertTrue(out.contains("00:02:00,300"))
    }

    @Test
    fun `clamps at zero rather than producing a negative timestamp`() {
        val out = serialize(shifted(parse("a.srt", srt), -60_000))
        assertTrue(out.contains("00:00:00,000"))
        assertTrue("No negative stamps", !out.contains("-00:"))
    }

    @Test
    fun `a zero shift changes nothing`() {
        val sub = parse("a.srt", srt)
        assertEquals(serialize(sub), serialize(shifted(sub, 0)))
    }

    @Test
    fun `keeps translations attached to their shifted timings`() {
        val sub = parse("a.srt", srt)
        sub.cues[0].translated = "প্রথম লাইন"
        val out = serialize(shifted(sub, 1_000))
        assertTrue(out.contains("00:00:02,000 --> 00:00:04,480\nপ্রথম লাইন"))
    }

    @Test
    fun `shifts ass dialogue lines`() {
        val ass = "[Events]\n" +
            "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            "Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,Hello\n"
        val out = serialize(shifted(parse("a.ass", ass), 2_000))
        assertTrue(out, out.contains("0:00:03.00,0:00:05.00"))
    }

    /* ------------------------------------------------------------- tidy */

    @Test
    fun `drops a line repeated from the one before it`() {
        val repeated = """
            1
            00:00:01,000 --> 00:00:02,000
            Hold on

            2
            00:00:02,000 --> 00:00:03,000
            Hold on

            3
            00:00:03,000 --> 00:00:04,000
            Let go
        """.trimIndent()
        val (out, removed) = tidied(parse("a.srt", repeated))
        assertEquals(1, removed)
        assertEquals(2, out.cues.size)
    }

    @Test
    fun `leaves a clean file untouched`() {
        val (out, removed) = tidied(parse("a.srt", srt))
        assertEquals(0, removed)
        assertEquals(2, out.cues.size)
    }

    /* -------------------------------------------------------- bilingual */

    @Test
    fun `stacks the translation above the source`() {
        val sub = parse("a.srt", srt)
        sub.cues[0].translated = "প্রথম লাইন"
        val out = serializeBilingual(sub)
        assertTrue(out.contains("প্রথম লাইন\nFirst line"))
    }

    @Test
    fun `does not double an untranslated line`() {
        val sub = parse("a.srt", srt)
        val out = serializeBilingual(sub)
        assertEquals(1, Regex("Second line").findAll(out).count())
    }
}
