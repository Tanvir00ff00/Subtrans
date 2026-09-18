package com.subtrans.app.engine

import com.subtrans.app.engine.QualityCheck.Flag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityCheckTest {

    private fun inspect(source: String, output: String, lost: Int = 0) =
        QualityCheck.inspect(source, output, "bn", lost)

    @Test
    fun `a clean translation raises nothing`() {
        val v = inspect("Where are we going?", "আমরা কোথায় যাচ্ছি?")
        assertFalse(v.suspicious)
        assertTrue(v.flags.isEmpty())
    }

    @Test
    fun `a dropped placeholder is always worth reviewing`() {
        val v = inspect("Naruto is here.", "এখানে আছে।", lost = 1)
        assertTrue(v.flags.contains(Flag.LOST_PLACEHOLDER))
    }

    @Test
    fun `output identical to the source means the translator gave up`() {
        val v = inspect("Where are we going?", "Where are we going?")
        assertTrue(v.flags.contains(Flag.UNCHANGED))
    }

    @Test
    fun `latin words left in a bengali translation are flagged`() {
        val v = inspect("He used the shadow clone.", "সে shadow clone ব্যবহার করল।")
        assertTrue(v.flags.contains(Flag.UNTRANSLATED_RUN))
    }

    @Test
    fun `latin output is fine when the target language uses latin script`() {
        val v = QualityCheck.inspect("Vamos a casa.", "Let us go home.", "en", 0)
        assertFalse(v.flags.contains(Flag.UNTRANSLATED_RUN))
    }

    @Test
    fun `tokens do not count as untranslated latin`() {
        // A line that is only a locked name must stay clean. The tag never
        // reaches the model at all, so it cannot be judged here either.
        val prepared = TermPrep.prepare(
            "<i>Rasengan</i>",
            listOf(GlossaryEntry("Rasengan", "রাসেনগান")),
        )
        val chunk = prepared.chunks.single()
        val v = QualityCheck.inspect(chunk, chunk, "bn", 0)
        assertFalse(v.suspicious)
    }

    /**
     * The wreckage the old `@0@` token left behind on real episodes: spaced
     * tokens and bare `@` runs written straight into a finished subtitle. If
     * anything like it ever reaches the output again, this has to notice.
     */
    @Test
    fun `token wreckage in the output is flagged`() {
        val v = QualityCheck.inspect("The only saving grace", "@ 0 @ একমাত্র সংরক্ষণ @ 1 @", "bn", 0)
        assertTrue(v.flags.contains(Flag.TOKEN_DEBRIS))
    }

    @Test
    fun `ordinary bengali output carries no debris flag`() {
        val v = QualityCheck.inspect("The only saving grace", "একমাত্র সান্ত্বনা", "bn", 0)
        assertFalse(v.flags.contains(Flag.TOKEN_DEBRIS))
    }

    @Test
    fun `a line worth retrying is told apart from one that is not`() {
        assertTrue(QualityCheck.worthRetrying(setOf(Flag.UNTRANSLATED_RUN)))
        assertTrue(QualityCheck.worthRetrying(setOf(Flag.TOKEN_DEBRIS)))
        // Short output is usually just short; retrying changes nothing.
        assertFalse(QualityCheck.worthRetrying(setOf(Flag.LENGTH_ANOMALY)))
    }

    @Test
    fun `a retry is kept only when it drops a serious flag`() {
        assertTrue(QualityCheck.isBetter(emptySet(), setOf(Flag.UNTRANSLATED_RUN)))
        assertFalse(QualityCheck.isBetter(setOf(Flag.UNTRANSLATED_RUN), setOf(Flag.UNTRANSLATED_RUN)))
        // Trading a length anomaly for a clean translation still counts as a win.
        assertTrue(QualityCheck.isBetter(setOf(Flag.LENGTH_ANOMALY), setOf(Flag.REPETITION)))
    }

    @Test
    fun `a wildly long output is flagged`() {
        val v = inspect("Good morning.", "শুভ সকাল শুভ সকাল শুভ সকাল শুভ সকাল শুভ সকাল শুভ সকাল")
        assertTrue(v.flags.contains(Flag.LENGTH_ANOMALY))
    }

    @Test
    fun `an empty output is flagged`() {
        val v = inspect("Something important happened.", "   ")
        assertTrue(v.flags.contains(Flag.LENGTH_ANOMALY))
    }

    @Test
    fun `bengali running longer than english is not an anomaly`() {
        val v = inspect("Stop.", "থামো।")
        assertFalse(v.flags.contains(Flag.LENGTH_ANOMALY))
    }

    @Test
    fun `three identical words in a row is degeneration`() {
        val v = inspect("I cannot believe it.", "আমি পারি পারি পারি না।")
        assertTrue(v.flags.contains(Flag.REPETITION))
    }

    @Test
    fun `doubled words are ordinary emphasis, not degeneration`() {
        val v = inspect("No, no!", "না, না!")
        assertFalse(v.flags.contains(Flag.REPETITION))
    }

    @Test
    fun `short lines are not judged on length`() {
        // "Hm." to a single Bengali syllable is a huge ratio but perfectly fine.
        val v = inspect("Hm.", "হুম।")
        assertFalse(v.flags.contains(Flag.LENGTH_ANOMALY))
    }

    @Test
    fun `counts how many lines an episode would send to the AI`() {
        val lines = listOf(
            "Where are we going?" to "আমরা কোথায় যাচ্ছি?",
            "You will see." to "তুমি দেখবে।",
            "He used the shadow clone." to "সে shadow clone ব্যবহার করল।",
            "Let's go." to "Let's go.",
        )
        val flagged = lines.count { (s, o) -> inspect(s, o).suspicious }
        assertEquals(2, flagged)
    }
}
