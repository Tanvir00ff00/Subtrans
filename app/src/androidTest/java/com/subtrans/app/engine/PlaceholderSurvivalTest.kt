package com.subtrans.app.engine

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one assumption the whole glossary-lock design rests on: that a
 * placeholder handed to the offline translator comes back unchanged.
 *
 * Nothing on the JVM can answer this — it needs the real ML Kit model on a
 * real device. So this test tries several placeholder shapes against live
 * translation and prints exactly what each one turns into, then asserts that
 * the shape the app actually ships with survives.
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
        "at" to { i: Int -> "@$i@" },
        "brace" to { i: Int -> "{$i}" },
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

    @Test
    fun placeholdersSurviveTranslation() = runBlocking {
        val scores = mutableMapOf<String, Int>()

        for ((name, make) in shapes) {
            var kept = 0
            for (template in sentences) {
                val token = make(0)
                val source = template.format(token)
                val out = withTimeout(30_000) { engine.translate(source) }
                val survived = out.contains(token)
                if (survived) kept++
                Log.i(TAG, "[$name] survived=$survived  in=<$source>  out=<$out>")
            }
            scores[name] = kept
            Log.i(TAG, "[$name] TOTAL $kept/${sentences.size}")
        }

        Log.i(TAG, "SCORES: " + scores.entries.sortedByDescending { it.value }
            .joinToString { "${it.key}=${it.value}/${sentences.size}" })

        // Two placeholders in one line, which is the common case for a name
        // plus a styling tag.
        val twoUp = "@0@ shouted at @1@ across the courtyard."
        val twoOut = withTimeout(30_000) { engine.translate(twoUp) }
        Log.i(TAG, "[two] in=<$twoUp>  out=<$twoOut>")

        val shipped = scores["at"] ?: 0
        assertTrue(
            "The shipped placeholder shape @n@ survived only $shipped of ${sentences.size} " +
                "translations. Scores: $scores — pick the winner and change " +
                "Markup.PLACEHOLDER_PREFIX/SUFFIX.",
            shipped >= sentences.size - 1,
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
        val draft = withTimeout(30_000) { engine.translate(prepared.text) }
        val finished = TermPrep.finish(draft, prepared)

        Log.i(TAG, "[lock] prepared=<${prepared.text}>")
        Log.i(TAG, "[lock] draft=<$draft>")
        Log.i(TAG, "[lock] final=<${finished.text}>  lost=${finished.lost}")

        assertTrue("Locked name was lost: ${finished.text}", finished.text.contains("নারুতো"))
        assertTrue("Locked term was lost: ${finished.text}", finished.text.contains("রাসেনগান"))
    }

    /** What an untouched line looks like, for a quality sanity check. */
    @Test
    fun plainLinesTranslate() = runBlocking {
        for (line in listOf("Where are we going?", "You will see.", "Wait, stop!")) {
            val out = withTimeout(30_000) { engine.translate(line) }
            Log.i(TAG, "[plain] <$line>  ->  <$out>")
            assertTrue("Empty translation for: $line", out.isNotBlank())
        }
    }
}
