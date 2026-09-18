package com.subtrans.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchingTest {

    /* -------------------------------------------------- what to skip */

    @Test
    fun `a line with no letters needs no translation`() {
        assertFalse(Batching.needsTranslation("♪"))
        assertFalse(Batching.needsTranslation("..."))
        assertFalse(Batching.needsTranslation("!!"))
        assertFalse(Batching.needsTranslation("— —"))
        assertFalse(Batching.needsTranslation("   "))
    }

    @Test
    fun `a line with letters does need translation`() {
        assertTrue(Batching.needsTranslation("Hello"))
        assertTrue(Batching.needsTranslation("[sighs]"))
        assertTrue(Batching.needsTranslation("Ok!"))
    }

    /* ---------------------------------------------------- the plan */

    @Test
    fun `groups short lines into batches of the given size`() {
        val texts = List(20) { "line $it" }
        val plan = Batching.plan(texts, maxLines = 8)
        assertEquals(3, plan.batches.size)
        assertEquals(8, plan.batches[0].size)
        assertEquals(4, plan.batches[2].size)
    }

    @Test
    fun `every input appears exactly once across the batches`() {
        val texts = List(37) { "line $it" }
        val flattened = Batching.plan(texts, maxLines = 5).batches.flatten()
        assertEquals(texts.indices.toList(), flattened.sorted())
        assertEquals(37, flattened.size)
    }

    @Test
    fun `a multi-line cue still gets its own call if it was not flattened`() {
        // The safety net: unflattened text cannot be split back out reliably.
        val texts = listOf("one", "two\nlines", "three")
        val plan = Batching.plan(texts, maxLines = 8)
        assertEquals(3, plan.batches.size)
        assertEquals(listOf(1), plan.batches[1])
    }

    /* ------------------------------------------------- flatten and wrap */

    @Test
    fun `flattening collapses a display wrap into one sentence`() {
        assertEquals(
            "I used to be someone that no one cared about.",
            Batching.flatten("I used to be someone that\nno one cared about."),
        )
    }

    @Test
    fun `flattened cues can then share a call`() {
        // This is the change that took a real seven-episode batch from 949
        // calls down to 297: most subtitle cues wrap across two display lines,
        // and unflattened every one of them was translated on its own.
        val wrapped = List(16) { "line $it that\nwraps over two" }
        val before = Batching.plan(wrapped, maxLines = 8).batches.size
        val after = Batching.plan(wrapped.map { Batching.flatten(it) }, maxLines = 8).batches.size
        assertEquals(16, before)
        assertEquals(2, after)
    }

    @Test
    fun `flattening leaves a single-line cue untouched`() {
        assertEquals("Where are we going?", Batching.flatten("Where are we going?"))
    }

    @Test
    fun `flattening does not disturb ASS breaks, which are placeholders by then`() {
        val prepared = Markup.protect("first\\Nsecond")
        assertEquals(prepared.text, Batching.flatten(prepared.text))
    }

    @Test
    fun `a short line is not re-wrapped`() {
        assertEquals("চলো তাহলে।", Batching.rewrap("চলো তাহলে।", width = 42))
    }

    @Test
    fun `a long line is split into two balanced lines`() {
        val long = "one two three four five six seven eight nine ten eleven twelve"
        val wrapped = Batching.rewrap(long, width = 20)
        val lines = wrapped.split("\n")
        assertEquals(2, lines.size)
        // Balanced, not a full line above a single stray word.
        assertTrue(lines[1].length > long.length / 4)
        assertEquals(long, wrapped.replace("\n", " "))
    }

    @Test
    fun `re-wrapping never loses or reorders words`() {
        val long = "আমি বিশ্বাস করতে পারছি না তুমি এতদূর এসেছ বন্ধু আজকে"
        assertEquals(long, Batching.rewrap(long, width = 20).replace("\n", " "))
    }

    @Test
    fun `a width of zero leaves the line alone`() {
        val long = "one two three four five six seven eight nine ten"
        assertEquals(long, Batching.rewrap(long, width = 0))
    }

    @Test
    fun `a single very long word is left as it is`() {
        val word = "x".repeat(80)
        assertEquals(word, Batching.rewrap(word, width = 20))
    }

    @Test
    fun `a long line closes the batch early`() {
        val texts = listOf("short", "x".repeat(950), "short")
        val plan = Batching.plan(texts, maxLines = 8, maxChars = 900)
        assertTrue(plan.batches.size >= 2)
        assertEquals(texts.indices.toList(), plan.batches.flatten().sorted())
    }

    @Test
    fun `an empty input produces no batches`() {
        assertTrue(Batching.plan(emptyList()).batches.isEmpty())
    }

    /* --------------------------------------------------- the result */

    @Test
    fun `splits a well-formed result back into its lines`() {
        val parts = Batching.split("এক\nদুই\nতিন", 3)
        assertEquals(listOf("এক", "দুই", "তিন"), parts)
    }

    @Test
    fun `tolerates blank lines the model added`() {
        assertEquals(listOf("এক", "দুই"), Batching.split("এক\n\nদুই", 2))
    }

    @Test
    fun `refuses a result with the wrong number of lines`() {
        // This is the guard: a merged or dropped line would shift every
        // translation after it onto the wrong timestamp.
        assertNull(Batching.split("এক দুই তিন", 3))
        assertNull(Batching.split("এক\nদুই\nতিন\nচার", 3))
    }

    @Test
    fun `a single line is returned whole, newlines and all`() {
        assertEquals(listOf("এক\nদুই"), Batching.split("এক\nদুই", 1))
    }

    @Test
    fun `join and split are inverses for single-line texts`() {
        val texts = listOf("one", "two", "three")
        val joined = Batching.join(texts)
        assertEquals(texts, Batching.split(joined, texts.size))
    }
}
