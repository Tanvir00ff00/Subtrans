package com.subtrans.app.engine

/**
 * Inline markup protection.
 *
 * Subtitle text carries styling the translator must not touch: `<i>` tags in
 * SRT, `{\an8\fad(200,200)}` override blocks and `\N` breaks in ASS. Each one
 * is swapped for an opaque placeholder before translation and put back after,
 * so styling survives whatever the engine does to the words around it.
 *
 * The placeholder shape matters more here than it did on the web. A neural
 * translation model will happily mangle an exotic character, but it leaves
 * short ASCII run-ins like `@3@` alone because they carry no meaning it can
 * translate. [PLACEHOLDER_PREFIX] and [PLACEHOLDER_SUFFIX] are deliberately
 * kept in one place so the shape can be retuned after measuring real output.
 */
object Markup {

    const val PLACEHOLDER_PREFIX = "@"
    const val PLACEHOLDER_SUFFIX = "@"

    // Every brace and bracket is escaped on purpose. Android compiles regexes
    // with ICU, which rejects a bare `}` that the desktop JVM quietly accepts —
    // so an unescaped one passes unit tests and then crashes on a phone.
    private val PATTERNS = listOf(
        Regex("""\{[^{}]*\}"""),       // ASS override blocks
        Regex("""<[^<>]+>"""),         // HTML-ish tags (SRT/VTT)
        Regex("""\\[Nnh]"""),          // ASS line breaks and hard spaces
    )

    /** Matches any placeholder this object produces. */
    private val PLACEHOLDER = Regex(
        Regex.escape(PLACEHOLDER_PREFIX) + """(\d+)""" + Regex.escape(PLACEHOLDER_SUFFIX)
    )

    data class Protected(val text: String, val tokens: List<String>)

    fun protect(text: String, startIndex: Int = 0): Protected {
        val tokens = mutableListOf<String>()
        var out = text
        for (pattern in PATTERNS) {
            out = pattern.replace(out) { match ->
                tokens += match.value
                placeholder(startIndex + tokens.size - 1)
            }
        }
        return Protected(out, tokens)
    }

    fun placeholder(index: Int): String = "$PLACEHOLDER_PREFIX$index$PLACEHOLDER_SUFFIX"

    /**
     * Puts the original markup back where the placeholders ended up.
     *
     * The lambda overload of [Regex.replace] uses the returned string
     * literally, so the token must NOT be escaped here — escaping it would
     * turn `{\an8}` into `{\\an8}` and break the very styling this is
     * protecting.
     */
    fun restore(text: String, tokens: List<String>, startIndex: Int = 0): String {
        if (tokens.isEmpty()) return text
        return PLACEHOLDER.replace(text) { match ->
            val i = match.groupValues[1].toIntOrNull()?.minus(startIndex)
            if (i != null && i in tokens.indices) tokens[i] else match.value
        }
    }

    /** Strips any placeholder the engine failed to carry through. */
    fun scrub(text: String): String = PLACEHOLDER.replace(text, "")

    /** True when every placeholder handed out came back. */
    fun intact(text: String, tokens: List<String>, startIndex: Int = 0): Boolean =
        tokens.indices.all { text.contains(placeholder(startIndex + it)) }

    /** How many of the placeholders handed out survived. */
    fun survivors(text: String, tokens: List<String>, startIndex: Int = 0): Int =
        tokens.indices.count { text.contains(placeholder(startIndex + it)) }
}
