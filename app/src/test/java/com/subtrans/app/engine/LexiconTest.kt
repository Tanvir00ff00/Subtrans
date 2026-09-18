package com.subtrans.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every case here is a line the offline model actually got wrong on the seven
 * Boruto episodes, or a line it must be left alone to handle.
 */
class LexiconTest {

    private fun bn(line: String) = Lexicon.lookup(line, "bn")

    /* ------------------------------------------------ the measured failures */

    @Test
    fun `Fine is an agreement, not a monetary penalty`() {
        // The model returned জরিমানা — the fine you pay.
        assertEquals("ঠিক আছে।", bn("Fine."))
    }

    @Test
    fun `I am fine is not an announcement of issuing fines`() {
        assertEquals("আমি ঠিক আছি।", bn("I'm fine."))
    }

    @Test
    fun `Huh is a question, not a tag question`() {
        // The model returned তাই না? — "isn't it?", the opposite meaning.
        assertEquals("অ্যাঁ?", bn("Huh?"))
    }

    @Test
    fun `Bye-bye is a farewell, not sleep`() {
        // The model returned ঘুম! — sleep.
        assertEquals("টা-টা!", bn("Bye-bye!"))
    }

    @Test
    fun `Damn it keeps some meaning`() {
        // The model returned এটা! — "this!".
        assertEquals("ধুর ছাই!", bn("Damn it!"))
    }

    /* ----------------------------------------------------------- endings */

    @Test
    fun `a full stop becomes a dari`() {
        assertEquals("ধন্যবাদ।", bn("Thank you."))
    }

    @Test
    fun `an exclamation mark is carried over`() {
        assertEquals("ধন্যবাদ!", bn("Thank you!"))
    }

    @Test
    fun `a doubled ending is kept whole`() {
        assertEquals("অ্যাঁ?!", bn("Huh?!"))
    }

    @Test
    fun `an ellipsis stays an ellipsis rather than three dari`() {
        assertEquals("দাঁড়াও...", bn("Wait..."))
    }

    @Test
    fun `a line with no ending gets none added`() {
        assertEquals("সবাই", bn("Everyone"))
    }

    /* -------------------------------------------------------- normalising */

    @Test
    fun `case does not matter`() {
        assertEquals(bn("Okay."), bn("OKAY."))
        assertEquals("ঠিক আছে।", bn("okay."))
    }

    @Test
    fun `surrounding space does not matter`() {
        assertEquals("ঠিক আছে।", bn("   Fine.   "))
    }

    @Test
    fun `screen text in capitals is matched`() {
        assertEquals("হাসপাতাল", bn("HOSPITAL"))
    }

    /* ------------------------------------------------------ what it refuses */

    @Test
    fun `a word inside a longer line is left to the model`() {
        // "Fine" is doing different work here and a table cannot tell.
        assertNull(bn("Fine, let's go."))
        assertNull(bn("That's a fine idea."))
    }

    @Test
    fun `an unknown line is left to the model`() {
        assertNull(bn("The hawk flew over the mountain."))
    }

    @Test
    fun `an empty or punctuation-only line is left alone`() {
        assertNull(bn(""))
        assertNull(bn("   "))
        assertNull(bn("..."))
        assertNull(bn("♪"))
    }

    @Test
    fun `a language with no table gets nothing`() {
        assertNull(Lexicon.lookup("Fine.", "de"))
        assertFalse(Lexicon.covers("de"))
        assertTrue(Lexicon.covers("bn"))
        assertTrue(Lexicon.covers("bn-BD"))
    }

    /* ------------------------------------------------------------ the table */

    @Test
    fun `the table can be listed for the user to correct`() {
        val entries = Lexicon.entries("bn")
        assertTrue(entries.size > 20)
        assertTrue(entries.all { it.source.isNotBlank() && it.target.isNotBlank() })
        // Sorted, so the screen showing it is readable.
        assertEquals(entries.map { it.source }.sorted(), entries.map { it.source })
    }

    @Test
    fun `no entry carries its own terminal punctuation`() {
        // Endings are added by lookup; baking them in would double them up.
        for (entry in Lexicon.entries("bn")) {
            assertFalse(entry.source, entry.source.endsWith("."))
            assertFalse(entry.target, entry.target.endsWith("।"))
            assertFalse(entry.target, entry.target.endsWith("!"))
        }
    }
}
