package com.subtrans.app.engine

import com.subtrans.app.engine.LanguageGuard.Script
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageGuardTest {

    @Test
    fun `recognises the script a line is written in`() {
        assertEquals(Script.LATIN, LanguageGuard.dominantScript("Where are we going?"))
        assertEquals(Script.BENGALI, LanguageGuard.dominantScript("আমরা কোথায় যাচ্ছি?"))
        assertEquals(Script.DEVANAGARI, LanguageGuard.dominantScript("हम कहाँ जा रहे हैं"))
    }

    @Test
    fun `refuses to judge a line with too few letters`() {
        // Music notes, sound effects and short interjections prove nothing.
        assertEquals(Script.NONE, LanguageGuard.dominantScript("♪"))
        assertEquals(Script.NONE, LanguageGuard.dominantScript("...!"))
        assertEquals(Script.NONE, LanguageGuard.dominantScript("Hm."))
    }

    @Test
    fun `a mostly-bengali line counts as bengali even with a latin word in it`() {
        // Deliberate: translating this would corrupt the Bengali half, so the
        // majority has to win rather than the line being called ambiguous.
        assertEquals(Script.BENGALI, LanguageGuard.dominantScript("ok ঠিক"))
        assertTrue(LanguageGuard.alreadyInTarget("ok ঠিক", "bn"))
    }

    @Test
    fun `an evenly split line stays undecided rather than guessing`() {
        assertEquals(Script.NONE, LanguageGuard.dominantScript("abcd আমরা"))
    }

    @Test
    fun `maps language tags to their scripts`() {
        assertEquals(Script.BENGALI, LanguageGuard.scriptOf("bn"))
        assertEquals(Script.DEVANAGARI, LanguageGuard.scriptOf("hi"))
        assertEquals(Script.ARABIC, LanguageGuard.scriptOf("ur"))
        assertEquals(Script.LATIN, LanguageGuard.scriptOf("en"))
        assertEquals(Script.LATIN, LanguageGuard.scriptOf("es"))
    }

    /* ------------------------------------------------------- per line */

    @Test
    fun `a line already in the target language is left alone`() {
        assertTrue(LanguageGuard.alreadyInTarget("আমরা কোথায় যাচ্ছি?", "bn"))
        assertFalse(LanguageGuard.alreadyInTarget("Where are we going?", "bn"))
    }

    @Test
    fun `the per-line guard stays out of the way for latin targets`() {
        // English and Spanish share an alphabet, so the script proves nothing.
        assertFalse(LanguageGuard.alreadyInTarget("Vamos a casa", "en"))
    }

    /* ------------------------------------------------------- per file */

    private val englishLines = listOf(
        "Where are we going?",
        "You will see.",
        "Wait, stop!",
        "I told him to wait for us.",
        "Without you we cannot win.",
        "Let's go, then.",
    )

    private val bengaliLines = listOf(
        "আমরা কোথায় যাচ্ছি?",
        "তুমি দেখবে।",
        "দাঁড়াও, থামো!",
        "আমি ওকে অপেক্ষা করতে বলেছিলাম।",
        "তোমাকে ছাড়া আমরা জিততে পারব না।",
        "চলো তাহলে।",
    )

    @Test
    fun `an english file bound for bengali is cleared to run`() {
        val verdict = LanguageGuard.inspectFile(englishLines, "en", "bn")
        assertFalse(verdict.alreadyTranslated)
        assertFalse(verdict.wrongSource)
    }

    @Test
    fun `a file already in bengali is caught before it can be damaged`() {
        // This is the case that ruined a real episode: a file named
        // "..._English.srt" that actually held Bengali.
        val verdict = LanguageGuard.inspectFile(bengaliLines, "en", "bn")
        assertTrue(verdict.alreadyTranslated)
    }

    @Test
    fun `a file in neither language is reported as the wrong source`() {
        val hindi = listOf(
            "हम कहाँ जा रहे हैं",
            "तुम देख लोगे",
            "रुको, ठहरो",
            "मैंने उससे इंतज़ार करने को कहा",
            "तुम्हारे बिना हम नहीं जीत सकते",
            "तो चलो फिर",
        )
        val verdict = LanguageGuard.inspectFile(hindi, "en", "bn")
        assertTrue(verdict.wrongSource)
        assertFalse(verdict.alreadyTranslated)
    }

    @Test
    fun `a mostly translated file still counts as translated`() {
        val mixed = bengaliLines + englishLines.take(2)
        assertTrue(LanguageGuard.inspectFile(mixed, "en", "bn").alreadyTranslated)
    }

    @Test
    fun `a short file is not judged at all`() {
        // Too little evidence to act on; better to translate than to refuse.
        val verdict = LanguageGuard.inspectFile(bengaliLines.take(3), "en", "bn")
        assertFalse(verdict.alreadyTranslated)
        assertFalse(verdict.wrongSource)
    }

    @Test
    fun `music and punctuation do not sway the verdict`() {
        // Lines with almost no letters carry no evidence either way.
        val noise = listOf("♪", "...", "!!", "♪♪", "-")
        val verdict = LanguageGuard.inspectFile(englishLines + noise, "en", "bn")
        assertEquals(englishLines.size, verdict.judgedLines)
        assertFalse(verdict.alreadyTranslated)
    }

    @Test
    fun `a bracketed english sound effect is still english`() {
        // "[sighs]" has real letters in it, so it counts — and correctly so,
        // because it is a line that does need translating.
        assertEquals(Script.LATIN, LanguageGuard.dominantScript("[sighs]"))
    }
}
