package com.subtrans.app.subtitle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These are the same cases that guarded the prototype parser. They exist to
 * catch the one failure that silently ruins a subtitle: text landing against
 * the wrong timestamp.
 */
class SubtitleTest {

    private val srt = """
        1
        00:00:01,000 --> 00:00:03,480
        Morning already?

        2
        00:00:03,600 --> 00:00:06,120
        <i>He had not slept at all.</i>
        Not one minute.

        3
        00:00:06,300 --> 00:00:08,000
        Let's go, then.
    """.trimIndent()

    /* ------------------------------------------------------------- SRT */

    @Test
    fun `reads every cue and keeps multi-line text together`() {
        val sub = parse("ep01.srt", srt)
        assertEquals(SubFormat.SRT, sub.format)
        assertEquals(3, sub.cues.size)
        assertEquals("<i>He had not slept at all.</i>\nNot one minute.", sub.cues[1].text)
    }

    @Test
    fun `round-trips untouched text back to the same timings`() {
        val sub = parse("ep01.srt", srt)
        val out = serialize(sub)
        for (cue in sub.cues) {
            assertTrue(out.contains((cue.meta as CueMeta.Srt).timing))
        }
        assertEquals(3, parse("ep01.srt", out).cues.size)
    }

    @Test
    fun `puts translations in the right slots and leaves timings alone`() {
        val sub = parse("ep01.srt", srt)
        sub.cues[0].translated = "সকাল হয়ে গেল?"
        sub.cues[2].translated = "চলো তাহলে।"

        val out = serialize(sub)
        assertTrue(out.contains("00:00:01,000 --> 00:00:03,480\nসকাল হয়ে গেল?"))
        assertTrue(out.contains("00:00:06,300 --> 00:00:08,000\nচলো তাহলে।"))
        // An untranslated cue keeps its source text rather than going blank.
        assertTrue(out.contains("Not one minute."))
    }

    @Test
    fun `handles files that omit the cue numbers`() {
        val noIndex = "00:00:01,000 --> 00:00:02,000\nFirst\n\n00:00:02,000 --> 00:00:03,000\nSecond\n"
        val sub = parse("x.srt", noIndex)
        assertEquals(2, sub.cues.size)
        // Serialising renumbers them from one.
        assertTrue(serialize(sub).startsWith("1\n00:00:01,000"))
    }

    @Test
    fun `survives CRLF line endings and a byte order mark`() {
        val sub = parse("x.srt", "﻿1\r\n00:00:01,000 --> 00:00:02,000\r\nHello\r\n")
        assertEquals(1, sub.cues.size)
        assertEquals("Hello", sub.cues[0].text)
    }

    /* ------------------------------------------------------------- VTT */

    private val vtt = """
        WEBVTT

        NOTE ripped from a stream

        intro
        00:00:01.000 --> 00:00:03.000
        Where are we going?

        00:00:03.200 --> 00:00:05.000
        You will see.
    """.trimIndent()

    @Test
    fun `keeps the header and note blocks out of the cue list`() {
        val sub = parse("ep01.vtt", vtt)
        assertEquals(SubFormat.VTT, sub.format)
        assertEquals(2, sub.cues.size)
        assertEquals("Where are we going?", sub.cues[0].text)
    }

    @Test
    fun `round-trips the vtt header, cue id and timings`() {
        val sub = parse("ep01.vtt", vtt)
        sub.cues[0].translated = "আমরা কোথায় যাচ্ছি?"

        val out = serialize(sub)
        assertTrue(out.startsWith("WEBVTT"))
        assertTrue(out.contains("NOTE ripped from a stream"))
        assertTrue(out.contains("intro\n00:00:01.000 --> 00:00:03.000\nআমরা কোথায় যাচ্ছি?"))
    }

    /* ------------------------------------------------------------- ASS */

    private val ass = """
        [Script Info]
        Title: Sample
        ScriptType: v4.00+

        [V4+ Styles]
        Format: Name, Fontname, Fontsize
        Style: Default,Arial,48

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,{\an8}Wait, stop!
        Dialogue: 0,0:00:03.20,0:00:05.00,Default,,0,0,0,,One, two, three.
        Comment: 0,0:00:05.00,0:00:06.00,Default,,0,0,0,,not a real line
    """.trimIndent()

    @Test
    fun `picks up dialogue lines only, and text containing commas`() {
        val sub = parse("ep01.ass", ass)
        assertEquals(SubFormat.ASS, sub.format)
        assertEquals(2, sub.cues.size)
        assertEquals("""{\an8}Wait, stop!""", sub.cues[0].text)
        assertEquals("One, two, three.", sub.cues[1].text)
    }

    @Test
    fun `swaps only the text field and leaves the rest of the file intact`() {
        val sub = parse("ep01.ass", ass)
        sub.cues[0].translated = """{\an8}দাঁড়াও!"""

        val out = serialize(sub)
        assertTrue(out.contains("[Script Info]"))
        assertTrue(out.contains("Style: Default,Arial,48"))
        assertTrue(out.contains("""Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,{\an8}দাঁড়াও!"""))
        // Untranslated dialogue and comments come through unchanged.
        assertTrue(out.contains("Dialogue: 0,0:00:03.20,0:00:05.00,Default,,0,0,0,,One, two, three."))
        assertTrue(out.contains("Comment: 0,0:00:05.00,0:00:06.00,Default,,0,0,0,,not a real line"))
    }

    @Test
    fun `writes multi-line translations back as ASS line breaks`() {
        val sub = parse("ep01.ass", ass)
        sub.cues[1].translated = "এক, দুই\nতিন।"
        assertTrue(serialize(sub).contains("""এক, দুই\Nতিন।"""))
    }

    /* ------------------------------------------------------ detection */

    @Test
    fun `trusts the extension first`() {
        assertEquals(SubFormat.ASS, detectFormat("a.ASS", ""))
        assertEquals(SubFormat.VTT, detectFormat("a.vtt", ""))
    }

    @Test
    fun `sniffs the content when the name is useless`() {
        assertEquals(SubFormat.VTT, detectFormat("subtitle", "WEBVTT\n\n"))
        assertEquals(SubFormat.ASS, detectFormat("subtitle", "[Script Info]\n"))
        assertEquals(SubFormat.SRT, detectFormat("subtitle", "1\n00:00:01,000 --> 00:00:02,000\nhi"))
    }

    /* ----------------------------------------------------- outputName */

    @Test
    fun `adds the language tag`() {
        assertEquals("Boruto - 042.bn.srt", outputName("Boruto - 042.srt", "bn"))
    }

    @Test
    fun `replaces an existing language tag instead of stacking one on`() {
        assertEquals("Boruto - 042.bn.srt", outputName("Boruto - 042.en.srt", "bn"))
        assertEquals("show.bn.ass", outputName("show.eng.ass", "bn"))
    }
}
