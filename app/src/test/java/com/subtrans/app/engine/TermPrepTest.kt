package com.subtrans.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offline translator is simulated here by doing to the prepared chunks what
 * a real one does: it rewrites the words and — where the test says so — abuses
 * the tokens exactly the way ML Kit was measured abusing them on seven real
 * Boruto episodes.
 */
class TermPrepTest {

    private val glossary = listOf(
        GlossaryEntry("Naruto Uzumaki", "নারুতো উজুমাকি"),
        GlossaryEntry("Naruto", "নারুতো"),
        GlossaryEntry("Rasengan", "রাসেনগান"),
        GlossaryEntry("Ino", "ইনো"),
    )

    /** The single chunk of a one-chunk line, which is nearly every line. */
    private fun TermPrep.Prepared.only(): String {
        assertEquals("expected a single chunk, got $chunks", 1, chunks.size)
        return chunks.first()
    }

    /* --------------------------------------------------------- glossary */

    @Test
    fun `hides glossary terms from the translator`() {
        val prepared = TermPrep.prepare("Naruto used the Rasengan.", glossary)
        assertFalse(prepared.only().contains("Naruto"))
        assertFalse(prepared.only().contains("Rasengan"))
        assertEquals(2, prepared.termCount)
    }

    @Test
    fun `puts the agreed spelling back, not a transliteration`() {
        val prepared = TermPrep.prepare("Naruto used the Rasengan.", glossary)
        val translated = prepared.chunks.map { it.replace("used the", "ব্যবহার করল") }
        val finished = TermPrep.finish(translated, prepared)

        assertTrue(finished.text.contains("নারুতো"))
        assertTrue(finished.text.contains("রাসেনগান"))
        assertEquals(0, finished.lost)
    }

    @Test
    fun `prefers the longer term when two overlap`() {
        val prepared = TermPrep.prepare("Naruto Uzumaki is here.", glossary)
        assertEquals(1, prepared.termCount)
        assertTrue(TermPrep.finish(prepared.chunks, prepared).text.contains("নারুতো উজুমাকি"))
    }

    @Test
    fun `does not match a term inside a longer word`() {
        val prepared = TermPrep.prepare("Inobi walked away.", glossary)
        assertEquals(0, prepared.termCount)
        assertTrue(prepared.only().contains("Inobi"))
    }

    @Test
    fun `matches a shouted name in capitals`() {
        val prepared = TermPrep.prepare("NARUTO!", glossary)
        assertEquals(1, prepared.termCount)
        assertEquals("নারুতো!", TermPrep.finish(prepared.chunks, prepared).text)
    }

    @Test
    fun `restores a term that appears more than once in a line`() {
        val prepared = TermPrep.prepare("Naruto, listen. Naruto!", glossary)
        assertEquals(2, prepared.termCount)
        val finished = TermPrep.finish(prepared.chunks, prepared)
        assertEquals(2, Regex("নারুতো").findAll(finished.text).count())
    }

    @Test
    fun `survives a target spelling that contains a dollar sign`() {
        // Regex replacement treats $ specially; the restore must not.
        val odd = listOf(GlossaryEntry("Price", "\$দাম"))
        val prepared = TermPrep.prepare("Price is high.", odd)
        assertTrue(TermPrep.finish(prepared.chunks, prepared).text.contains("\$দাম"))
    }

    @Test
    fun `ignores glossary rows that are half filled in`() {
        val half = listOf(GlossaryEntry("Sakura", ""), GlossaryEntry("", "কিছু"))
        val prepared = TermPrep.prepare("Sakura waited.", half)
        assertEquals(0, prepared.termCount)
        assertEquals("Sakura waited.", prepared.only())
    }

    /**
     * The measured failure, reproduced. ML Kit pads a token with spaces, so
     * `Xq0q` comes back as `Xq 0 q`. Matching has to tolerate that or the junk
     * is written straight into the subtitle — which is what happened to 111
     * lines of one real episode under the old `@0@` token.
     */
    @Test
    fun `restores a token the model padded with spaces`() {
        val prepared = TermPrep.prepare("Naruto is here.", glossary)
        val mangled = prepared.chunks.map { chunk ->
            Regex("""Xq(\d+)q""").replace(chunk) { "Xq ${it.groupValues[1]} q" }
        }
        val finished = TermPrep.finish(mangled, prepared)

        assertEquals(0, finished.lost)
        assertTrue(finished.text, finished.text.contains("নারুতো"))
        assertFalse(finished.text, finished.text.contains("Xq"))
    }

    @Test
    fun `reports tokens the translator dropped`() {
        val prepared = TermPrep.prepare("Naruto used the Rasengan.", glossary)
        val mangled = prepared.chunks.map { it.replace(Markup.placeholder(1), "") }
        val finished = TermPrep.finish(mangled, prepared)

        assertEquals(1, finished.lost)
        assertTrue(finished.text.contains("নারুতো") || finished.text.contains("রাসেনগান"))
    }

    @Test
    fun `offers a token-free version of the same line to retry with`() {
        val prepared = TermPrep.prepare("Naruto used the Rasengan.", glossary)
        val plain = prepared.plainChunks().single()

        assertFalse(plain.contains("Xq"))
        assertTrue(plain.contains("Naruto"))
        assertTrue(plain.contains("Rasengan"))
    }

    /* ----------------------------------------------------------- markup */

    @Test
    fun `never shows the translator a styling tag`() {
        val prepared = TermPrep.prepare("<i>Naruto</i>", glossary)
        assertFalse(prepared.only().contains("<"))
        assertFalse(prepared.only().contains(">"))
        assertEquals("<i>নারুতো</i>", TermPrep.finish(prepared.chunks, prepared).text)
    }

    /**
     * The shape that broke a real episode: a sentence split across two display
     * lines, each half separately italicised. It must arrive at the model as
     * one whole sentence with no tags in it.
     */
    @Test
    fun `sends a split italic sentence as one chunk`() {
        val prepared = TermPrep.prepare(
            "<i>When they realized I was here,</i>\n<i>they tried to get rid of me...</i>",
            emptyList(),
        )
        val chunk = prepared.only()
        assertFalse(chunk, chunk.contains("<"))
        assertTrue(chunk, chunk.contains("When they realized"))
        assertTrue(chunk, chunk.contains("get rid of me"))

        val finished = TermPrep.finish(listOf("যখন তারা বুঝল"), prepared)
        assertEquals("<i>যখন তারা বুঝল</i>", finished.text)
    }

    @Test
    fun `keeps ASS override blocks and line breaks`() {
        val prepared = TermPrep.prepare("""{\an8}Naruto\Nis late""", glossary)
        val finished = TermPrep.finish(prepared.chunks, prepared)
        assertEquals("""{\an8}নারুতো\Nis late""", finished.text)
    }

    @Test
    fun `drops emphasis on part of a line rather than splitting the sentence`() {
        val prepared = TermPrep.prepare("It <i>does</i> make a difference!", emptyList())
        assertTrue(prepared.stylingDropped)
        assertEquals("It does make a difference!", prepared.only())
        assertEquals(
            "এতে পার্থক্য হয়!",
            TermPrep.finish(listOf("এতে পার্থক্য হয়!"), prepared).text,
        )
    }

    @Test
    fun `leaves a line with nothing to protect completely alone`() {
        val prepared = TermPrep.prepare("Just ordinary words.", glossary)
        assertEquals("Just ordinary words.", prepared.only())
        assertTrue(prepared.restores.isEmpty())
        assertEquals("Just ordinary words.", TermPrep.finish(prepared.chunks, prepared).text)
    }

    /* ------------------------------------------------------- AI repairs */

    @Test
    fun `an AI repair comes back inside the original styling`() {
        val prepared = TermPrep.prepare("<i>Naruto is late</i>", glossary)
        val corrected = "নারুতো দেরি করেছে"
        assertEquals("<i>নারুতো দেরি করেছে</i>", TermPrep.finishWhole(corrected, prepared).text)
    }

    @Test
    fun `an AI repair still gets its locked names back`() {
        val prepared = TermPrep.prepare("Naruto is late", glossary)
        val corrected = "${Markup.placeholder(0)} দেরি করেছে"
        val finished = TermPrep.finishWhole(corrected, prepared)
        assertEquals("নারুতো দেরি করেছে", finished.text)
        assertEquals(0, finished.lost)
    }
}
