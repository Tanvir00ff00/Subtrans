package com.subtrans.app.engine

/**
 * Preparing a line for the offline translator, and putting it back together.
 *
 * A machine translator's worst habit on fiction is mangling proper nouns:
 * "Rasengan" comes back transliterated three different ways across three
 * episodes, and character names turn into ordinary words. The fix is to never
 * let the translator see them. Every glossary term is swapped for a
 * placeholder before translation and replaced with the agreed spelling after,
 * exactly like the styling tags. The translator only ever handles the words
 * that actually need translating.
 *
 * Both kinds of substitution share one numbering, so restoring is a single
 * pass and a placeholder that goes missing is detectable either way.
 */

@kotlinx.serialization.Serializable
data class GlossaryEntry(
    val source: String,
    val target: String,
    val note: String = "",
)

object TermPrep {

    data class Prepared(
        /** What to hand the translator. */
        val text: String,
        /** Placeholder index -> the string it must become again. */
        val restores: List<String>,
        /** How many of the restores came from the glossary rather than markup. */
        val termCount: Int,
    )

    data class Finished(
        val text: String,
        /** Placeholders the translator dropped. Non-zero means review this line. */
        val lost: Int,
    )

    fun prepare(text: String, glossary: List<GlossaryEntry>): Prepared {
        // Markup first: a term sitting inside a tag must not be locked twice.
        val markup = Markup.protect(text)
        val restores = markup.tokens.toMutableList()

        var out = markup.text
        var termCount = 0

        // Longest first, so "Naruto Uzumaki" wins over "Naruto".
        for (entry in glossary.sortedByDescending { it.source.length }) {
            val source = entry.source.trim()
            if (source.isEmpty() || entry.target.isBlank()) continue

            val pattern = termPattern(source)
            if (!pattern.containsMatchIn(out)) continue

            out = pattern.replace(out) {
                restores += entry.target
                termCount++
                Markup.placeholder(restores.size - 1)
            }
        }

        return Prepared(out, restores, termCount)
    }

    fun finish(translated: String, prepared: Prepared): Finished {
        val lost = prepared.restores.size - Markup.survivors(translated, prepared.restores)
        val restored = Markup.restore(translated, prepared.restores)
        return Finished(Markup.scrub(restored).trim(), lost)
    }

    /**
     * Word-bounded where the term's own edges are word characters, so "Ino"
     * does not match inside "Inobi", while a term like "Mr." still matches.
     */
    private fun termPattern(source: String): Regex {
        val head = if (source.first().isLetterOrDigit()) "\\b" else ""
        val tail = if (source.last().isLetterOrDigit()) "\\b" else ""
        return Regex(head + Regex.escape(source) + tail, RegexOption.IGNORE_CASE)
    }
}
