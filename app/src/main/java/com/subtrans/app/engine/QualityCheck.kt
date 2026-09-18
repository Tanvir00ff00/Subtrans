package com.subtrans.app.engine

/**
 * Deciding which lines the offline translator got wrong.
 *
 * This is what keeps the AI budget small. The on-device model handles every
 * line for free; only the lines that fail these checks are worth spending an
 * AI call on. On a typical episode that is a handful out of three hundred,
 * which is the difference between a season costing twenty calls and ninety
 * thousand.
 *
 * Every check here is pure string work, so it runs instantly and is testable
 * without a device.
 */
object QualityCheck {

    enum class Flag {
        /** A styling tag or locked name never came back. */
        LOST_PLACEHOLDER,

        /** Output is word-for-word the input: the model had nothing to offer. */
        UNCHANGED,

        /** Latin words remain although the target language uses another script. */
        UNTRANSLATED_RUN,

        /** Output is wildly longer or shorter than the source. */
        LENGTH_ANOMALY,

        /** The same word repeats over and over — classic model degeneration. */
        REPETITION,
    }

    data class Verdict(val flags: Set<Flag>) {
        val suspicious: Boolean get() = flags.isNotEmpty()
    }

    private val LATIN_RUN = Regex("""[A-Za-z]{3,}""")
    private val WORD = Regex("""\S+""")
    private val PLACEHOLDER_ANY = Regex(
        Regex.escape(Markup.PLACEHOLDER_PREFIX) + """\d+""" + Regex.escape(Markup.PLACEHOLDER_SUFFIX)
    )

    /**
     * Language tags whose writing system is Latin. For any other target, Latin
     * words left in the output mean the translator skipped them.
     */
    private val LATIN_SCRIPT_TAGS = setOf(
        "en", "es", "fr", "de", "it", "pt", "nl", "sv", "no", "da", "fi", "pl",
        "cs", "sk", "hr", "ro", "hu", "tr", "id", "ms", "vi", "sw", "af", "et",
        "lv", "lt", "sl", "sq", "eu", "ca", "cy", "ga", "gl", "is", "tl",
    )

    fun inspect(
        source: String,
        output: String,
        targetTag: String,
        lostPlaceholders: Int,
    ): Verdict {
        val flags = mutableSetOf<Flag>()

        if (lostPlaceholders > 0) flags += Flag.LOST_PLACEHOLDER

        val bareSource = strip(source)
        val bareOutput = strip(output)

        // Nothing to judge on a line that was only a tag or a locked name.
        if (bareSource.isBlank()) return Verdict(flags)

        if (bareOutput.isBlank()) {
            flags += Flag.LENGTH_ANOMALY
            return Verdict(flags)
        }

        if (bareSource.equals(bareOutput, ignoreCase = true) && LATIN_RUN.containsMatchIn(bareSource)) {
            flags += Flag.UNCHANGED
        }

        if (targetTag.lowercase().substringBefore('-') !in LATIN_SCRIPT_TAGS &&
            LATIN_RUN.containsMatchIn(bareOutput)
        ) {
            flags += Flag.UNTRANSLATED_RUN
        }

        val ratio = bareOutput.length.toDouble() / bareSource.length
        // Bengali runs longer than English, so the generous side is deliberate.
        if (bareSource.length >= 12 && (ratio > 3.0 || ratio < 0.3)) {
            flags += Flag.LENGTH_ANOMALY
        }

        if (hasRunawayRepetition(bareOutput)) flags += Flag.REPETITION

        return Verdict(flags)
    }

    /** Placeholders and whitespace are noise for every judgement here. */
    private fun strip(text: String): String =
        PLACEHOLDER_ANY.replace(text, " ").replace(Regex("""\s+"""), " ").trim()

    /**
     * Three or more identical words in a row. Two is ordinary emphasis
     * ("no no"), three is the model stuck in a loop.
     */
    private fun hasRunawayRepetition(text: String): Boolean {
        val words = WORD.findAll(text).map { it.value }.toList()
        if (words.size < 3) return false
        var run = 1
        for (i in 1 until words.size) {
            run = if (words[i].equals(words[i - 1], ignoreCase = true)) run + 1 else 1
            if (run >= 3) return true
        }
        return false
    }
}
