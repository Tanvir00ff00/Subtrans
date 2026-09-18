package com.subtrans.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offline translator is simulated here by doing to the prepared text what
 * a real one does: it rewrites the words and leaves the short ASCII
 * placeholders alone. That is enough to prove the round trip.
 */
class TermPrepTest {

    private val glossary = listOf(
        GlossaryEntry("Naruto Uzumaki", "নারুতো উজুমাকি"),
        GlossaryEntry("Naruto", "নারুতো"),
        GlossaryEntry("Rasengan", "রাসেনগান"),
        GlossaryEntry("Ino", "ইনো"),
    )

    @Test
    fun `hides glossary terms from the translator`() {
        val prepared = TermPrep.prepare("Naruto used the Rasengan.", glossary)
        assertFalse(prepared.text.contains("Naruto"))
        assertFalse(prepared.text.contains("Rasengan"))
        assertEquals(2, prepared.termCount)
    }

    @Test
    fun `puts the agreed spelling back, not a transliteration`() {
        val prepared = TermPrep.prepare("Naruto used the Rasengan.", glossary)
        // What a translator returns: words changed, placeholders untouched.
        val translated = prepared.text.replace("used the", "ব্যবহার করল")
        val finished = TermPrep.finish(translated, prepared)

        assertTrue(finished.text.contains("নারুতো"))
        assertTrue(finished.text.contains("রাসেনগান"))
        assertEquals(0, finished.lost)
    }

    @Test
    fun `prefers the longer term when two overlap`() {
        val prepared = TermPrep.prepare("Naruto Uzumaki is here.", glossary)
        val finished = TermPrep.finish(prepared.text, prepared)
        assertTrue(finished.text.contains("নারুতো উজুমাকি"))
        assertEquals(1, prepared.termCount)
    }

    @Test
    fun `does not match a term inside a longer word`() {
        val prepared = TermPrep.prepare("Inobi walked away.", glossary)
        assertEquals(0, prepared.termCount)
        assertTrue(prepared.text.contains("Inobi"))
    }

    @Test
    fun `matches a shouted name in capitals`() {
        val prepared = TermPrep.prepare("NARUTO!", glossary)
        assertEquals(1, prepared.termCount)
        assertEquals("নারুতো!", TermPrep.finish(prepared.text, prepared).text)
    }

    @Test
    fun `keeps styling tags through the round trip`() {
        val prepared = TermPrep.prepare("<i>Naruto</i>", glossary)
        assertFalse(prepared.text.contains("<i>"))
        assertEquals("<i>নারুতো</i>", TermPrep.finish(prepared.text, prepared).text)
    }

    @Test
    fun `keeps ASS override blocks and line breaks`() {
        val prepared = TermPrep.prepare("""{\an8}Naruto\Nis late""", glossary)
        val finished = TermPrep.finish(prepared.text, prepared)
        assertEquals("""{\an8}নারুতো\Nis late""", finished.text)
    }

    @Test
    fun `reports placeholders the translator dropped`() {
        val prepared = TermPrep.prepare("Naruto used the Rasengan.", glossary)
        // A translator that swallowed one of the placeholders.
        val mangled = prepared.text.replace(Markup.placeholder(1), "")
        val finished = TermPrep.finish(mangled, prepared)

        assertEquals(1, finished.lost)
        // Whatever survived is still restored properly.
        assertTrue(finished.text.contains("নারুতো") || finished.text.contains("রাসেনগান"))
    }

    @Test
    fun `leaves a line with nothing to protect completely alone`() {
        val prepared = TermPrep.prepare("Just ordinary words.", glossary)
        assertEquals("Just ordinary words.", prepared.text)
        assertTrue(prepared.restores.isEmpty())
        assertEquals("Just ordinary words.", TermPrep.finish(prepared.text, prepared).text)
    }

    @Test
    fun `ignores glossary rows that are half filled in`() {
        val half = listOf(GlossaryEntry("Sakura", ""), GlossaryEntry("", "কিছু"))
        val prepared = TermPrep.prepare("Sakura waited.", half)
        assertEquals(0, prepared.termCount)
        assertEquals("Sakura waited.", prepared.text)
    }

    @Test
    fun `restores a term that appears more than once in a line`() {
        val prepared = TermPrep.prepare("Naruto, listen. Naruto!", glossary)
        assertEquals(2, prepared.termCount)
        val finished = TermPrep.finish(prepared.text, prepared)
        assertEquals(2, Regex("নারুতো").findAll(finished.text).count())
    }

    @Test
    fun `survives a target spelling that contains a dollar sign`() {
        // Regex replacement treats $ specially; the restore must not.
        val odd = listOf(GlossaryEntry("Price", "\$দাম"))
        val prepared = TermPrep.prepare("Price is high.", odd)
        assertTrue(TermPrep.finish(prepared.text, prepared).text.contains("\$দাম"))
    }
}
