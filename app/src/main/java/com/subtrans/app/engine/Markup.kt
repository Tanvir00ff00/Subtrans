package com.subtrans.app.engine

/**
 * Keeping styling out of the translator's way.
 *
 * The first version of this file swapped every tag for a short placeholder —
 * `<i>` became `@0@` — on the theory that a translation model leaves meaningless
 * ASCII run-ins alone. Seven real episodes proved that wrong in three separate
 * ways, and the failures were loud:
 *
 *   1. ML Kit pads the token with spaces. `@0@` comes back as `@ 0 @`, the
 *      restore pattern no longer matches, and the junk is written straight into
 *      the subtitle. In one episode this happened on 111 of 299 lines.
 *   2. Sometimes the token stops the model translating at all: the input is
 *      echoed back verbatim and an English line lands in a Bengali file.
 *   3. Sometimes the model degenerates and returns a row of bare `@`, losing
 *      the sentence entirely.
 *
 * The share of broken lines tracked the share of italic lines almost exactly
 * across all six translated files, which is as clear as a diagnosis gets.
 *
 * So markup is no longer encoded for the model — it never reaches the model.
 * A line is cut into literal markup and translatable text, only the text is
 * sent, and the pieces are reassembled afterwards. There is nothing left for a
 * translator to mangle.
 */
object Markup {

    // Every brace and bracket is escaped on purpose. Android compiles regexes
    // with ICU, which rejects a bare `}` that the desktop JVM quietly accepts —
    // so an unescaped one passes unit tests and then crashes on a phone.
    private const val ASS_BLOCK = """\{[^{}]*\}"""
    private const val HTML_TAG = """<[^<>]+>"""
    private const val ASS_BREAK = """\\[Nnh]"""

    private val ANY = Regex("$ASS_BLOCK|$HTML_TAG|$ASS_BREAK")
    private val OPEN_TAG = Regex("""<\s*([A-Za-z][A-Za-z0-9]*)[^<>]*>""")
    private val CLOSE_TAG = Regex("""<\s*/\s*([A-Za-z][A-Za-z0-9]*)\s*>""")

    /** A run of the original line: either literal markup, or words to translate. */
    data class Piece(val text: String, val markup: Boolean)

    /** One position in the rebuilt line. */
    sealed interface Slot {
        /** Text copied through untouched — markup, or punctuation between tags. */
        data class Literal(val text: String) : Slot

        /** The place where translated chunk [index] belongs. */
        data class Chunk(val index: Int) : Slot
    }

    data class Layout(
        val slots: List<Slot>,
        /** What to hand the translator, in order. Usually exactly one entry. */
        val chunks: List<String>,
        /**
         * True when inline styling was deliberately discarded because keeping
         * it would have chopped a sentence into fragments. Losing italics on a
         * word is cosmetic; handing the model "It", "does", "make a difference"
         * as three separate calls is not.
         */
        val stylingDropped: Boolean = false,
    )

    /** Cuts a line into alternating markup and text runs, left to right. */
    fun split(text: String): List<Piece> {
        val pieces = mutableListOf<Piece>()
        var last = 0
        for (match in ANY.findAll(text)) {
            if (match.range.first > last) {
                pieces += Piece(text.substring(last, match.range.first), markup = false)
            }
            pieces += Piece(match.value, markup = true)
            last = match.range.last + 1
        }
        if (last < text.length) pieces += Piece(text.substring(last), markup = false)
        return pieces
    }

    private fun hasLetters(text: String): Boolean = text.any { it.isLetter() }

    /** Override blocks and line breaks change layout; tags only change looks. */
    private fun isStructural(markup: String): Boolean = !markup.startsWith("<")

    /**
     * Works out how to translate a line without ever showing the translator a
     * tag. Three shapes, in order of preference:
     *
     *  - **Whole-line styling.** `<i>everything</i>`, including the very common
     *    `<i>first half</i> <i>second half</i>`. The shell is lifted off, the
     *    words go over as one sentence, and the shell goes back on. This is the
     *    overwhelming majority of real subtitle markup.
     *  - **Structural markup.** ASS override blocks and `\N` breaks change where
     *    and how a line is drawn, so they are kept exactly and the text around
     *    them is translated in place.
     *  - **Inline emphasis on part of a line.** The styling is dropped and the
     *    sentence is translated whole. See [Layout.stylingDropped].
     */
    fun layout(text: String): Layout {
        val pieces = split(text)
        val markup = pieces.filter { it.markup }
        if (markup.isEmpty()) return Layout(listOf(Slot.Chunk(0)), listOf(text))

        if (markup.any { isStructural(it.text) }) return segmented(pieces)

        shell(pieces)?.let { return it }

        // Inline emphasis that does not wrap the whole line.
        val whole = pieces.filterNot { it.markup }.joinToString("") { it.text }
        return Layout(listOf(Slot.Chunk(0)), listOf(whole), stylingDropped = true)
    }

    /**
     * Detects a line whose words all sit inside one kind of tag, and lifts that
     * tag off. Returns null when the line is not that shape.
     */
    private fun shell(pieces: List<Piece>): Layout? {
        val names = pieces.filter { it.markup }.map { piece ->
            OPEN_TAG.matchEntire(piece.text)?.groupValues?.get(1)?.lowercase()
                ?: CLOSE_TAG.matchEntire(piece.text)?.groupValues?.get(1)?.lowercase()
                ?: return null
        }
        if (names.distinct().size != 1) return null

        var depth = 0
        var lettered = 0
        val words = StringBuilder()

        for (piece in pieces) {
            if (piece.markup) {
                if (CLOSE_TAG.matchEntire(piece.text) != null) depth-- else depth++
                if (depth < 0) return null
                continue
            }
            // Words outside the tag pair mean this is not a whole-line shell.
            if (depth == 0 && hasLetters(piece.text)) return null
            if (hasLetters(piece.text)) lettered++
            words.append(piece.text)
        }
        if (depth != 0 || lettered == 0) return null

        val open = pieces.first { it.markup }.text
        val close = pieces.last { it.markup }.text
        if (CLOSE_TAG.matchEntire(close) == null) return null

        return Layout(
            slots = listOf(Slot.Literal(open), Slot.Chunk(0), Slot.Literal(close)),
            chunks = listOf(words.toString()),
        )
    }

    /** Keeps every piece of markup exactly where it was, translating around it. */
    private fun segmented(pieces: List<Piece>): Layout {
        val slots = mutableListOf<Slot>()
        val chunks = mutableListOf<String>()
        for (piece in pieces) {
            if (!piece.markup && hasLetters(piece.text)) {
                slots += Slot.Chunk(chunks.size)
                chunks += piece.text
            } else {
                slots += Slot.Literal(piece.text)
            }
        }
        return Layout(slots, chunks)
    }

    /** Puts a translated line back together from its layout. */
    fun assemble(layout: Layout, translated: List<String>): String =
        buildString {
            for (slot in layout.slots) {
                when (slot) {
                    is Slot.Literal -> append(slot.text)
                    is Slot.Chunk -> append(translated.getOrNull(slot.index) ?: "")
                }
            }
        }

    /* ------------------------------------------------------------------ *
     * Glossary tokens.
     *
     * Unlike markup, a locked name has to travel inside the sentence — pulling
     * it out would leave the model translating half-clauses. So tokens survive
     * here, but with the lessons of `@0@` applied: the token is now shaped like
     * an ordinary proper noun (letters and digits, no punctuation) so the model
     * treats it as a name and passes it through, and matching tolerates the
     * spaces a model likes to sprinkle in anyway.
     * ------------------------------------------------------------------ */

    const val TOKEN_PREFIX = "Xq"
    const val TOKEN_SUFFIX = "q"

    private val TOKEN = Regex("""[Xx]\s*[Qq]\s*(\d+)\s*[Qq]""")

    fun placeholder(index: Int): String = "$TOKEN_PREFIX$index$TOKEN_SUFFIX"

    /**
     * Puts locked names back where the tokens ended up.
     *
     * The lambda overload of [Regex.replace] uses the returned string
     * literally, so the replacement must NOT be escaped here — escaping it
     * would turn a target spelling containing `$` into visible backslashes.
     */
    fun restore(text: String, tokens: List<String>, startIndex: Int = 0): String {
        if (tokens.isEmpty()) return text
        return TOKEN.replace(text) { match ->
            val i = match.groupValues[1].toIntOrNull()?.minus(startIndex)
            if (i != null && i in tokens.indices) tokens[i] else match.value
        }
    }

    /** Strips any token the engine failed to carry through. */
    fun scrub(text: String): String = TOKEN.replace(text, "")

    /** How many of the tokens handed out survived, spaces and all. */
    fun survivors(text: String, tokens: List<String>, startIndex: Int = 0): Int {
        if (tokens.isEmpty()) return 0
        val seen = TOKEN.findAll(text).mapNotNull { it.groupValues[1].toIntOrNull() }.toSet()
        return tokens.indices.count { startIndex + it in seen }
    }

    /** True when every token handed out came back. */
    fun intact(text: String, tokens: List<String>, startIndex: Int = 0): Boolean =
        survivors(text, tokens, startIndex) == tokens.size
}
