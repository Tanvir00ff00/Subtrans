package com.subtrans.app.engine

import com.subtrans.app.engine.LanguageGuard.Script
import com.subtrans.app.engine.SourcePlan.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePlanTest {

    /** Stands in for the offline engine's model list. */
    private val supported = setOf("en", "hi", "bn", "ja", "es", "ar")
    private val supports: (String) -> Boolean = { it in supported }

    private fun decide(
        detected: String?,
        script: Script = Script.LATIN,
        configured: String = "en",
        target: String = "bn",
    ) = SourcePlan.decide(detected, script, configured, target, supports)

    /* ------------------------------------------------ the ordinary case */

    @Test
    fun `an english file is translated from english`() {
        assertEquals(Decision.Translate("en", true), decide("en", Script.LATIN))
    }

    @Test
    fun `a hindi file in the same batch is translated from hindi`() {
        // The whole point: one batch, two source languages, no settings change.
        assertEquals(Decision.Translate("hi", true), decide("hi", Script.DEVANAGARI))
    }

    @Test
    fun `a japanese file is translated from japanese`() {
        assertEquals(Decision.Translate("ja", true), decide("ja", Script.KANA))
    }

    /* ------------------------------------------- already in the target */

    @Test
    fun `a file already in bengali is passed through, not translated again`() {
        assertEquals(Decision.PassThrough, decide("bn", Script.BENGALI))
    }

    @Test
    fun `the script wins when the identifier disagrees about a bengali file`() {
        // Identifiers are probabilistic; the writing system is not. Rewriting a
        // Bengali file because a guesser said "hi" would destroy it.
        assertEquals(Decision.PassThrough, decide("hi", Script.BENGALI))
    }

    @Test
    fun `a bengali file with no identifier opinion is still passed through`() {
        assertEquals(Decision.PassThrough, decide(null, Script.BENGALI))
    }

    @Test
    fun `passing through applies to a latin target only on the identifier's word`() {
        // English and Spanish share an alphabet, so script proves nothing here.
        assertEquals(Decision.PassThrough, decide("en", Script.LATIN, target = "en"))
        assertTrue(decide("es", Script.LATIN, target = "en") is Decision.Translate)
    }

    /* --------------------------------------------------- uncertain input */

    @Test
    fun `an unreadable file falls back to the configured source, flagged as a guess`() {
        val decision = decide(null, Script.NONE, configured = "en")
        assertEquals(Decision.Translate("en", false), decision)
    }

    @Test
    fun `the literal und result is treated as no opinion`() {
        assertEquals(Decision.Translate("en", false), decide("und", Script.NONE))
    }

    @Test
    fun `a blank result is treated as no opinion`() {
        assertEquals(Decision.Translate("en", false), decide("", Script.NONE))
    }

    @Test
    fun `a regional tag is reduced to its base language`() {
        assertEquals(Decision.Translate("en", true), decide("en-GB", Script.LATIN))
    }

    /* ------------------------------------------------------ unsupported */

    @Test
    fun `a language the engine cannot handle is reported, never silently dropped`() {
        val decision = decide("cy", Script.LATIN)
        assertEquals(Decision.Unsupported("cy"), decision)
    }

    @Test
    fun `an unsupported configured fallback is reported too`() {
        val decision = SourcePlan.decide(null, Script.NONE, "cy", "bn", supports)
        assertEquals(Decision.Unsupported(null), decision)
    }

    /* -------------------------------------------------------- grouping */

    @Test
    fun `files are grouped so each language model loads once`() {
        val files = listOf("a" to "en", "b" to "hi", "c" to "en", "d" to "ja", "e" to "en")
        val grouped = SourcePlan.groupBySource(files) { it.second }

        assertEquals(setOf("en", "hi", "ja"), grouped.keys)
        assertEquals(3, grouped["en"]?.size)
        assertEquals(1, grouped["hi"]?.size)
        // Nothing is lost in the grouping.
        assertEquals(files.size, grouped.values.sumOf { it.size })
    }

    @Test
    fun `a hundred mixed files all reach a decision`() {
        // The scenario that prompted this: a large archive where a handful are
        // already done. Every file must come back with a plan of some kind.
        val detected = List(100) { i ->
            when {
                i % 20 == 0 -> "bn"
                i % 3 == 0 -> "hi"
                else -> "en"
            }
        }
        val decisions = detected.map { tag ->
            decide(tag, if (tag == "bn") Script.BENGALI else Script.LATIN)
        }

        assertEquals(100, decisions.size)
        assertEquals(5, decisions.count { it is Decision.PassThrough })
        assertEquals(95, decisions.count { it is Decision.Translate })
        assertEquals(0, decisions.count { it is Decision.Unsupported })
    }
}
