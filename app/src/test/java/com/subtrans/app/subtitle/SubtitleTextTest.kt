package com.subtrans.app.subtitle

import com.subtrans.app.subtitle.SubtitleText.Encoding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTextTest {

    private val line = "1\n00:00:01,000 --> 00:00:02,000\nCafé — আমরা কোথায়?\n"

    @Test
    fun `plain utf-8 is read as utf-8`() {
        val decoded = SubtitleText.decode(line.toByteArray(Charsets.UTF_8))
        assertEquals(Encoding.UTF8, decoded.encoding)
        assertEquals(line, decoded.text)
    }

    @Test
    fun `a utf-8 byte order mark is stripped, not left in the first cue`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            line.toByteArray(Charsets.UTF_8)
        val decoded = SubtitleText.decode(bytes)
        assertEquals(Encoding.UTF8_BOM, decoded.encoding)
        assertEquals(line, decoded.text)
        assertFalse(decoded.text.startsWith("﻿"))
    }

    @Test
    fun `utf-16 little endian with a mark comes back intact`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + line.toByteArray(Charsets.UTF_16LE)
        val decoded = SubtitleText.decode(bytes)
        assertEquals(Encoding.UTF16_LE, decoded.encoding)
        assertEquals(line, decoded.text)
    }

    @Test
    fun `utf-16 big endian with a mark comes back intact`() {
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + line.toByteArray(Charsets.UTF_16BE)
        val decoded = SubtitleText.decode(bytes)
        assertEquals(Encoding.UTF16_BE, decoded.encoding)
        assertEquals(line, decoded.text)
    }

    @Test
    fun `utf-16 without a mark is recognised by its nul bytes`() {
        val ascii = "1\n00:00:01,000 --> 00:00:02,000\nHello there friend\n"
        val decoded = SubtitleText.decode(ascii.toByteArray(Charsets.UTF_16LE))
        assertEquals(Encoding.UTF16_LE, decoded.encoding)
        assertEquals(ascii, decoded.text)
    }

    @Test
    fun `windows-1252 accents survive instead of turning into question marks`() {
        val latin = "1\n00:00:01,000 --> 00:00:02,000\nCafé fermé\n"
        val decoded = SubtitleText.decode(latin.toByteArray(charset("windows-1252")))
        assertEquals(Encoding.WINDOWS_1252, decoded.encoding)
        assertTrue(decoded.text, decoded.text.contains("Café fermé"))
    }

    @Test
    fun `a genuine replacement character is not mistaken for bad encoding`() {
        // U+FFFD can appear legitimately; the decoder validates byte sequences
        // rather than counting replacement characters after the fact.
        val withFffd = "hello � world"
        val decoded = SubtitleText.decode(withFffd.toByteArray(Charsets.UTF_8))
        assertEquals(Encoding.UTF8, decoded.encoding)
        assertEquals(withFffd, decoded.text)
    }

    @Test
    fun `empty input does not throw`() {
        assertEquals("", SubtitleText.decode(ByteArray(0)).text)
    }

    @Test
    fun `a decoded utf-16 file actually parses into cues`() {
        // The real-world failure: UTF-16 read as UTF-8 still yields a file that
        // parses, so nothing downstream notices the text is ruined.
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nHello there\n\n" +
            "2\n00:00:03,000 --> 00:00:04,000\nGoodbye now\n"
        val decoded = SubtitleText.decode(srt.toByteArray(Charsets.UTF_16LE))
        val sub = parse("ep01.srt", decoded.text)
        assertEquals(2, sub.cues.size)
        assertEquals("Hello there", sub.cues[0].text)
    }

    @Test
    fun `bengali text round-trips through the decoder`() {
        val bengali = "1\n00:00:01,000 --> 00:00:02,000\nআমরা কোথায় যাচ্ছি?\n"
        val decoded = SubtitleText.decode(bengali.toByteArray(Charsets.UTF_8))
        assertTrue(decoded.text.contains("আমরা কোথায় যাচ্ছি?"))
    }
}
