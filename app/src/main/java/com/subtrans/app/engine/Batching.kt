package com.subtrans.app.engine

/**
 * Making the offline engine fast enough for a whole season.
 *
 * Translating a 430-line episode one line at a time means 430 round trips into
 * the model, and the per-call overhead dominates: the work itself is fast, the
 * calling is not. Three cheap changes remove most of that cost, in increasing
 * order of care required:
 *
 *   1. Lines with no letters — "♪", "...", "!!" — need no translation at all.
 *   2. Repeated lines are common in subtitles (sound effects, short replies),
 *      and only need translating once.
 *   3. Several lines can ride in one call, joined by newlines.
 *
 * The third is the big win and the only risky one, because the model might
 * merge or drop a line and silently shift everything after it — the exact class
 * of bug this project keeps running into. So a batch result is only accepted
 * when the line count comes back exactly right; otherwise the caller redoes
 * that batch one line at a time. A slow correct answer beats a fast wrong one.
 */
object Batching {

    /** A line with no letters carries nothing a translator could change. */
    fun needsTranslation(text: String): Boolean = text.any { it.isLetter() }

    data class Plan(
        /** Indices into the input list, grouped per call. */
        val batches: List<List<Int>>,
    )

    /**
     * Where a subtitle breaks across two display lines is a layout decision,
     * not a meaning one. Collapsing the break lets the cue share a call with
     * others and — more importantly — hands the model a whole sentence instead
     * of the first half of one.
     *
     * ASS line breaks are untouched by this: [Markup] has already replaced
     * them with placeholders before the text gets here.
     */
    fun flatten(text: String): String =
        text.replace('\n', ' ').replace(Regex("""[ \t]{2,}"""), " ").trim()

    /**
     * Re-wraps a translated line for display. Subtitles are read at a glance,
     * so a long single line is worse than two short ones.
     */
    fun rewrap(text: String, width: Int = 42, maxLines: Int = 2): String {
        if (width <= 0 || text.length <= width || text.contains('\n')) return text

        val words = text.split(' ').filter { it.isNotEmpty() }
        if (words.size < 2) return text

        // Balance the lines rather than filling the first one greedily: two
        // even lines read better than a full line above a single word.
        val target = if (maxLines >= 2) (text.length + 1) / 2 else text.length
        val lines = mutableListOf<StringBuilder>()
        var current = StringBuilder()

        for (word in words) {
            val wouldBe = if (current.isEmpty()) word.length else current.length + 1 + word.length
            if (current.isNotEmpty() && wouldBe > target && lines.size < maxLines - 1) {
                lines += current
                current = StringBuilder(word)
            } else {
                if (current.isNotEmpty()) current.append(' ')
                current.append(word)
            }
        }
        lines += current

        return lines.joinToString("\n")
    }

    /**
     * Groups texts into calls. A text that still contains a newline is given a
     * call of its own, because it could not be split back out reliably — but
     * callers are expected to have run [flatten] first, so that is a safety
     * net rather than the normal path.
     */
    fun plan(texts: List<String>, maxLines: Int = 8, maxChars: Int = 900): Plan {
        val batches = mutableListOf<List<Int>>()
        var current = mutableListOf<Int>()
        var chars = 0

        fun flush() {
            if (current.isNotEmpty()) {
                batches += current
                current = mutableListOf()
                chars = 0
            }
        }

        for ((index, text) in texts.withIndex()) {
            if (text.contains('\n')) {
                flush()
                batches += listOf(index)
                continue
            }
            if (current.size >= maxLines || (current.isNotEmpty() && chars + text.length > maxChars)) {
                flush()
            }
            current += index
            chars += text.length
        }
        flush()

        return Plan(batches)
    }

    fun join(texts: List<String>): String = texts.joinToString("\n")

    /**
     * Splits a batch result back into its lines, or returns null when the
     * model did not return exactly the expected number — which is the signal
     * to fall back to one call per line rather than guess at the alignment.
     */
    fun split(output: String, expected: Int): List<String>? {
        if (expected == 1) return listOf(output)

        val parts = output.split("\n")
        if (parts.size == expected) return parts

        // Some models add or collapse blank lines; dropping the empty ones is
        // worth one more attempt before giving up on the batch.
        val tightened = parts.filter { it.isNotBlank() }
        if (tightened.size == expected) return tightened

        return null
    }
}
