package com.subtrans.app.engine

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.AfterClass
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a token actually does to a real translation.
 *
 * An earlier version of this test asked one question — does the token come
 * back? — and that question was too small. The shipped `@0@` token did come
 * back often enough to pass it, and still ruined six real episodes, because
 * survival is only half of what matters:
 *
 *   * ML Kit pads tokens with spaces, so `@0@` returns as `@ 0 @`. A strict
 *     `contains` check calls that survival; the production restore pattern
 *     called it a loss and wrote the junk into the subtitle.
 *   * A token can also *poison* the line around it — the model gives up and
 *     echoes the English back, or degenerates into a row of punctuation. The
 *     token survives perfectly in both cases.
 *
 * So a shape is now scored on three things: the tolerant matcher sees it, the
 * sentence around it actually reached Bengali, and nothing degenerated. Only
 * the glossary lock is measured here; styling no longer travels as a token at
 * all, because no shape passed these bars reliably enough to trust.
 *
 * Read the output with:
 *   adb logcat -s PlaceholderProbe
 */
@RunWith(AndroidJUnit4::class)
class PlaceholderSurvivalTest {

    companion object {
        private const val TAG = "PlaceholderProbe"
        private const val SOURCE = "en"
        private const val TARGET = "bn"

        private lateinit var engine: TranslationEngine

        @BeforeClass
        @JvmStatic
        fun setUp() = runBlocking {
            engine = requireNotNull(TranslationEngine.create(SOURCE, TARGET)) {
                "ML Kit has no model for $SOURCE -> $TARGET"
            }
            // The first run downloads roughly thirty megabytes.
            withTimeout(300_000) { engine.ensureModel(requireWifi = false) }
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            if (::engine.isInitialized) engine.close()
        }
    }

    /** Candidate shapes, each standing in for a locked name. */
    private val shapes = listOf(
        "shipped-Xq" to { i: Int -> "Xq${i}q" },
        "at" to { i: Int -> "@$i@" },
        "underscore" to { i: Int -> "__${i}__" },
        "hash" to { i: Int -> "#$i#" },
        "xtag" to { i: Int -> "x${i}x" },
        "bracket" to { i: Int -> "[$i]" },
    )

    private val sentences = listOf(
        "%s used the technique again.",
        "Where is %s going?",
        "%s! Stop right there!",
        "I told %s to wait for us.",
        "Without %s we cannot win this fight.",
    )

    private fun reachedTarget(text: String): Boolean =
        LanguageGuard.dominantScript(text) == LanguageGuard.Script.BENGALI

    private data class Score(val restored: Int, val translated: Int) {
        val clean: Int get() = minOf(restored, translated)
    }

    @Test
    fun tokensSurviveWithoutPoisoningTheSentence() = runBlocking {
        val scores = mutableMapOf<String, Score>()

        for ((name, make) in shapes) {
            var restored = 0
            var translated = 0

            for (template in sentences) {
                val token = make(0)
                val source = template.format(token)
                val out = withTimeout(30_000) { engine.translate(source) }

                // Judge survival the way production does, not more kindly.
                val seen = if (name == "shipped-Xq") {
                    Markup.survivors(out, listOf("নারুতো")) == 1
                } else {
                    out.contains(token)
                }
                // And check the model did not simply give up on the sentence.
                val stripped = out.replace(token, " ")
                val worked = reachedTarget(stripped) && !stripped.contains(template.substringBefore(" %s"))

                if (seen) restored++
                if (worked) translated++
                Log.i(TAG, "[$name] seen=$seen translated=$worked  in=<$source>  out=<$out>")
            }

            scores[name] = Score(restored, translated)
            Log.i(TAG, "[$name] TOTAL restored=$restored translated=$translated")
        }

        Log.i(
            TAG,
            "SCORES: " + scores.entries.sortedByDescending { it.value.clean }
                .joinToString { "${it.key}=${it.value.clean}/${sentences.size}" },
        )

        val shipped = scores.getValue("shipped-Xq")
        assertTrue(
            "The shipped token shape scored restored=${shipped.restored} " +
                "translated=${shipped.translated} of ${sentences.size}. " +
                "Full scores: $scores — pick a better winner and change " +
                "Markup.TOKEN_PREFIX/TOKEN_SUFFIX.",
            shipped.clean >= sentences.size - 1,
        )
    }

    /** The end-to-end path: prepare, translate, restore, and check the name. */
    @Test
    fun glossaryLockKeepsTheAgreedSpelling() = runBlocking {
        val glossary = listOf(
            GlossaryEntry("Naruto", "নারুতো"),
            GlossaryEntry("Rasengan", "রাসেনগান"),
        )
        val prepared = TermPrep.prepare("Naruto used the Rasengan again.", glossary)
        val draft = prepared.chunks.map { withTimeout(30_000) { engine.translate(it) } }
        val finished = TermPrep.finish(draft, prepared)

        Log.i(TAG, "[lock] prepared=<${prepared.chunks}>")
        Log.i(TAG, "[lock] draft=<$draft>")
        Log.i(TAG, "[lock] final=<${finished.text}>  lost=${finished.lost}")

        assertTrue("Locked name was lost: ${finished.text}", finished.text.contains("নারুতো"))
        assertTrue("Locked term was lost: ${finished.text}", finished.text.contains("রাসেনগান"))
    }

    /**
     * The exact line shape that broke episode 270: one sentence, split across
     * two display lines, each half wrapped in its own italic tag. Under the old
     * design this produced `@ 0 @ ... @ 1 @` in the finished file. It must now
     * come back as clean Bengali inside a single pair of tags.
     */
    @Test
    fun aSplitItalicSentenceComesBackClean() = runBlocking {
        val line = "<i>When they realized I was here,</i>\n<i>they tried to get rid of me...</i>"
        val prepared = TermPrep.prepare(line, emptyList())

        assertTrue("styling reached the model: ${prepared.chunks}", prepared.chunks.none { it.contains("<") })

        val draft = prepared.chunks.map {
            withTimeout(30_000) { engine.translate(Batching.flatten(it)) }
        }
        val finished = TermPrep.finish(draft, prepared)
        Log.i(TAG, "[italic] final=<${finished.text}>")

        assertFalse("token debris in output: ${finished.text}", finished.text.contains("@"))
        assertFalse("token debris in output: ${finished.text}", finished.text.contains("Xq"))
        assertTrue("styling lost: ${finished.text}", finished.text.startsWith("<i>"))
        assertTrue("styling lost: ${finished.text}", finished.text.endsWith("</i>"))
        assertTrue(
            "did not reach Bengali: ${finished.text}",
            reachedTarget(finished.text.removePrefix("<i>").removeSuffix("</i>")),
        )
    }

    /** What an untouched line looks like, for a quality sanity check. */
    @Test
    fun plainLinesTranslate() = runBlocking {
        for (line in listOf("Where are we going?", "You will see.", "Wait, stop!")) {
            val out = withTimeout(30_000) { engine.translate(line) }
            Log.i(TAG, "[plain] <$line>  ->  <$out>")
            assertTrue("did not reach Bengali: <$out>", reachedTarget(out))
        }
    }
}
