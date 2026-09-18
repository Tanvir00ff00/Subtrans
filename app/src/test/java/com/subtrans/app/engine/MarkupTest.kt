package com.subtrans.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * These cases are taken from the seven Boruto episodes that exposed the
 * placeholder bug. The rule they all enforce is the same one: whatever a line
 * looks like, the translator must never be handed a tag.
 */
class MarkupTest {

    private fun chunksOf(text: String) = Markup.layout(text).chunks

    private fun assertNoMarkup(chunks: List<String>) {
        for (chunk in chunks) {
            assertFalse("tag reached the model: <$chunk>", chunk.contains("<"))
            assertFalse("tag reached the model: <$chunk>", chunk.contains(">"))
            assertFalse("override block reached the model: <$chunk>", chunk.contains("{"))
        }
    }

    /* ------------------------------------------------------------ split */

    @Test
    fun `a plain line is one text piece`() {
        val pieces = Markup.split("Where are we going?")
        assertEquals(1, pieces.size)
        assertFalse(pieces.single().markup)
    }

    @Test
    fun `tags and words alternate in order`() {
        val pieces = Markup.split("a<i>b</i>c")
        assertEquals(listOf("a", "<i>", "b", "</i>", "c"), pieces.map { it.text })
        assertEquals(listOf(false, true, false, true, false), pieces.map { it.markup })
    }

    /* ----------------------------------------------------------- layout */

    @Test
    fun `a line with no markup is handed over whole`() {
        val layout = Markup.layout("Where are we going?")
        assertEquals(listOf("Where are we going?"), layout.chunks)
        assertFalse(layout.stylingDropped)
    }

    @Test
    fun `a wholly italic line loses its tags on the way out and gets them back`() {
        val layout = Markup.layout("<i>Farewell, Academy!</i>")
        assertEquals(listOf("Farewell, Academy!"), layout.chunks)
        assertNoMarkup(layout.chunks)
        assertEquals("<i>বিদায়!</i>", Markup.assemble(layout, listOf("বিদায়!")))
    }

    /**
     * Episode 270 was 54% italic and came back 37% broken. This is the shape
     * that did it: one sentence, two display lines, each separately wrapped.
     * It has to reach the model as a single sentence.
     */
    @Test
    fun `a sentence split across two italic display lines stays one chunk`() {
        val layout = Markup.layout("<i>When they realized I was here,</i>\n<i>they tried to get rid of me...</i>")
        assertEquals(1, layout.chunks.size)
        assertNoMarkup(layout.chunks)
        assertTrue(layout.chunks.single().contains("When they realized"))
        assertTrue(layout.chunks.single().contains("get rid of me"))
        assertFalse(layout.stylingDropped)
    }

    @Test
    fun `three italic fragments still make one sentence`() {
        val layout = Markup.layout("<i>one</i> <i>two</i> <i>three</i>")
        assertEquals(listOf("one two three"), layout.chunks)
        assertEquals("<i>এক দুই তিন</i>", Markup.assemble(layout, listOf("এক দুই তিন")))
    }

    @Test
    fun `a font tag is treated the same as an italic one`() {
        val layout = Markup.layout("""<font color="#ffffff">Hello there</font>""")
        assertEquals(listOf("Hello there"), layout.chunks)
        assertEquals(
            """<font color="#ffffff">হ্যালো</font>""",
            Markup.assemble(layout, listOf("হ্যালো")),
        )
    }

    @Test
    fun `emphasis on part of a line is dropped rather than split`() {
        val layout = Markup.layout("It <i>does</i> make a difference!")
        assertTrue(layout.stylingDropped)
        assertEquals(listOf("It does make a difference!"), layout.chunks)
        assertEquals("এতে হয়!", Markup.assemble(layout, listOf("এতে হয়!")))
    }

    @Test
    fun `a leading word before an italic tail is kept whole`() {
        val layout = Markup.layout("Farewell,\n<i>Academy!</i>")
        assertEquals(1, layout.chunks.size)
        assertTrue(layout.stylingDropped)
        assertNoMarkup(layout.chunks)
    }

    @Test
    fun `mismatched tag names do not make a shell`() {
        val layout = Markup.layout("<i><b>shout</b></i>")
        assertTrue(layout.stylingDropped)
        assertEquals(listOf("shout"), layout.chunks)
    }

    @Test
    fun `a stray closing tag does not throw`() {
        val layout = Markup.layout("</i>orphaned text")
        assertNoMarkup(layout.chunks)
        assertTrue(layout.chunks.any { it.contains("orphaned") })
    }

    /* ------------------------------------------------- structural markup */

    @Test
    fun `an ASS override block stays exactly where it was`() {
        val layout = Markup.layout("""{\an8}Top of the screen""")
        assertEquals(listOf("Top of the screen"), layout.chunks)
        assertEquals(
            """{\an8}পর্দার উপরে""",
            Markup.assemble(layout, listOf("পর্দার উপরে")),
        )
    }

    @Test
    fun `an ASS line break splits the line instead of vanishing`() {
        val layout = Markup.layout("""first\Nsecond""")
        assertEquals(listOf("first", "second"), layout.chunks)
        assertEquals("""এক\Nদুই""", Markup.assemble(layout, listOf("এক", "দুই")))
    }

    @Test
    fun `positioning is never dropped the way emphasis is`() {
        val layout = Markup.layout("""{\an8}It <i>does</i> matter""")
        assertFalse(layout.stylingDropped)
        assertTrue(Markup.assemble(layout, layout.chunks).contains("""{\an8}"""))
    }

    /* --------------------------------------------------------- assemble */

    @Test
    fun `assembling with a missing chunk leaves a gap rather than crashing`() {
        val layout = Markup.layout("<i>something</i>")
        assertEquals("<i></i>", Markup.assemble(layout, emptyList()))
    }

    @Test
    fun `every layout round-trips when the translation is the identity`() {
        val lines = listOf(
            "Plain words.",
            "<i>All italic.</i>",
            """{\an8}Positioned.""",
            """Broken\Nin two.""",
            "a<i>b</i>c",
        )
        for (line in lines) {
            val layout = Markup.layout(line)
            val back = Markup.assemble(layout, layout.chunks)
            if (layout.stylingDropped) {
                // Styling is the only thing allowed to go missing.
                assertEquals(line.replace(Regex("</?[A-Za-z][^<>]*>"), ""), back)
            } else {
                assertEquals(line, back)
            }
        }
    }

    /* ----------------------------------------------------------- tokens */

    @Test
    fun `a token padded with spaces is still recognised`() {
        val text = "Xq 0 q went home"
        assertEquals(1, Markup.survivors(text, listOf("নারুতো")))
        assertEquals("নারুতো went home", Markup.restore(text, listOf("নারুতো")))
    }

    @Test
    fun `a lowercased token is still recognised`() {
        assertEquals("নারুতো!", Markup.restore("xq0q!", listOf("নারুতো")))
    }

    @Test
    fun `a token the model swallowed is reported and scrubbed`() {
        val tokens = listOf("নারুতো", "রাসেনগান")
        val text = "Xq0q used it"
        assertEquals(1, Markup.survivors(text, tokens))
        assertFalse(Markup.intact(text, tokens))
        assertEquals("used it", Markup.scrub("Xq1q used it").trim())
    }
}
