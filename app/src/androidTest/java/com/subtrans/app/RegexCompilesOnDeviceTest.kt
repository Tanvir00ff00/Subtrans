package com.subtrans.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.subtrans.app.data.guessSeries
import com.subtrans.app.engine.GlossaryEntry
import com.subtrans.app.engine.Markup
import com.subtrans.app.engine.QualityCheck
import com.subtrans.app.engine.ReplaceRule
import com.subtrans.app.engine.TermPrep
import com.subtrans.app.subtitle.detectFormat
import com.subtrans.app.subtitle.outputName
import com.subtrans.app.subtitle.parse
import com.subtrans.app.subtitle.serialize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards a whole class of bug that JVM unit tests structurally cannot catch.
 *
 * Android compiles regular expressions with ICU, the desktop JVM with
 * java.util.regex, and ICU is stricter — an unescaped `}` or a `]` inside a
 * character class compiles fine on a laptop and throws
 * PatternSyntaxException from a static initialiser on a phone, taking the whole
 * class down with a NoClassDefFoundError that points nowhere useful.
 *
 * So this test does the cheapest possible thing on a real device: touch every
 * object that owns a regex and run one small operation through it. It needs no
 * model download and finishes in under a second, which makes it safe to run on
 * every change.
 */
@RunWith(AndroidJUnit4::class)
class RegexCompilesOnDeviceTest {

    @Test
    fun markupPatternsCompile() {
        val layout = Markup.layout("""{\an8}<i>Hello</i>\Nthere""")
        assertEquals(listOf("Hello", "there"), layout.chunks)
        assertEquals("""{\an8}<i>Hello</i>\Nthere""", Markup.assemble(layout, layout.chunks))
    }

    @Test
    fun tokenPatternsCompile() {
        // The tolerant form matters most on a device: this is where the model
        // that pads tokens with spaces actually lives.
        assertEquals("নারুতো!", Markup.restore("Xq 0 q!", listOf("নারুতো")))
        assertEquals(1, Markup.survivors("Xq0q", listOf("নারুতো")))
        assertTrue(Markup.scrub("Xq0q here").isNotEmpty())
    }

    @Test
    fun termPrepPatternsCompile() {
        val glossary = listOf(GlossaryEntry("Naruto", "নারুতো"))
        val prepared = TermPrep.prepare("""{\an8}Naruto is late""", glossary)
        val finished = TermPrep.finish(prepared.chunks, prepared)
        assertEquals("""{\an8}নারুতো is late""", finished.text)
        assertEquals(0, finished.lost)
    }

    @Test
    fun qualityCheckPatternsCompile() {
        val clean = QualityCheck.inspect("Where are we?", "আমরা কোথায়?", "bn", 0)
        assertTrue(clean.flags.isEmpty())
        val dirty = QualityCheck.inspect("Where are we?", "Where are we?", "bn", 0)
        assertTrue(dirty.suspicious)
    }

    @Test
    fun replaceRulePatternsCompile() {
        assertEquals("b", ReplaceRule("a", "b").apply("a"))
        assertEquals("b", ReplaceRule("""a{1}""", "b", regex = true).apply("a"))
        // A rule the user typed badly must not crash the whole run.
        assertEquals("a", ReplaceRule("""[unclosed""", "b", regex = true).apply("a"))
    }

    @Test
    fun subtitleParserPatternsCompile() {
        val srt = "1\n00:00:01,000 --> 00:00:02,000\nHello\n"
        val sub = parse("ep01.srt", srt)
        assertEquals(1, sub.cues.size)
        sub.cues[0].translated = "হ্যালো"
        assertTrue(serialize(sub).contains("হ্যালো"))

        val ass = "[Script Info]\n\n[Events]\n" +
            "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\n" +
            """Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,{\an8}Wait, stop!""" + "\n"
        val assSub = parse("ep01.ass", ass)
        assertEquals(1, assSub.cues.size)
        assertEquals("""{\an8}Wait, stop!""", assSub.cues[0].text)
    }

    @Test
    fun formatSniffingPatternsCompile() {
        assertEquals("vtt", detectFormat("x", "WEBVTT\n\n").name.lowercase())
        assertEquals("ass", detectFormat("x", "[Script Info]\n").name.lowercase())
    }

    @Test
    fun seriesGuessPatternsCompile() {
        assertEquals("Boruto", guessSeries("[SubsPlease] Boruto - 042 (1080p).en.srt"))
        assertEquals("Boruto", guessSeries("Boruto.S01E05.1080p.srt"))
    }

    @Test
    fun outputNamePatternsCompile() {
        assertEquals("Boruto - 042.bn.srt", outputName("Boruto - 042.en.srt", "bn"))
    }
}
