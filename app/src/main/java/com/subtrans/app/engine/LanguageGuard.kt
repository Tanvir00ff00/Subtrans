package com.subtrans.app.engine

/**
 * Stops the app translating something that is already translated.
 *
 * This exists because of a real failure. A file named `..._English.srt` turned
 * out to contain perfectly good Bengali. The app was configured for English to
 * Bengali, handed the Bengali to a model that expects English, and got back
 * wreckage: শিনোবির became শিশির, খারাপ became বিহারাপ. Every timestamp was
 * correct and every line was present, so nothing downstream noticed — the
 * output was simply ruined.
 *
 * The lesson is that a file's name is not evidence of its language. So the
 * script is checked instead, in two places: once per file before a run starts,
 * and again per line, because mixed files exist and one guard should not be
 * the only guard.
 */
object LanguageGuard {

    enum class Script { LATIN, BENGALI, DEVANAGARI, ARABIC, CYRILLIC, CJK, KANA, HANGUL, THAI, NONE }

    /** Which script a language tag is normally written in. */
    fun scriptOf(languageTag: String): Script = when (languageTag.lowercase().substringBefore('-')) {
        "bn", "as" -> Script.BENGALI
        "hi", "mr", "ne", "sa" -> Script.DEVANAGARI
        "ar", "fa", "ur", "ps" -> Script.ARABIC
        "ru", "uk", "bg", "sr", "mk", "be" -> Script.CYRILLIC
        "zh" -> Script.CJK
        "ja" -> Script.KANA
        "ko" -> Script.HANGUL
        "th" -> Script.THAI
        else -> Script.LATIN
    }

    private fun scriptOfChar(c: Char): Script = when (c.code) {
        in 0x0980..0x09FF -> Script.BENGALI
        in 0x0900..0x097F -> Script.DEVANAGARI
        in 0x0600..0x06FF, in 0x0750..0x077F -> Script.ARABIC
        in 0x0400..0x04FF -> Script.CYRILLIC
        in 0x3040..0x309F, in 0x30A0..0x30FF -> Script.KANA
        in 0xAC00..0xD7AF, in 0x1100..0x11FF -> Script.HANGUL
        in 0x0E00..0x0E7F -> Script.THAI
        in 0x4E00..0x9FFF, in 0x3400..0x4DBF -> Script.CJK
        else -> if (c.isLetter() && c.code < 0x0250) Script.LATIN else Script.NONE
    }

    /**
     * The script most of the letters in [text] belong to, or NONE when there
     * are too few letters to judge — a line of "♪" or "[sighs]" tells us
     * nothing and must not be counted as evidence either way.
     */
    fun dominantScript(text: String): Script {
        val counts = mutableMapOf<Script, Int>()
        var letters = 0

        for (c in text) {
            val script = scriptOfChar(c)
            if (script == Script.NONE) continue
            counts[script] = (counts[script] ?: 0) + 1
            letters++
        }

        if (letters < 4) return Script.NONE
        val (best, count) = counts.maxByOrNull { it.value } ?: return Script.NONE
        // A clear majority, not a plurality: mixed lines stay undecided.
        return if (count * 2 > letters) best else Script.NONE
    }

    /**
     * True when this line is already written in the target language's script
     * and translating it again would only corrupt it.
     */
    fun alreadyInTarget(text: String, targetTag: String): Boolean {
        val target = scriptOf(targetTag)
        // For a Latin target this test is useless — too many languages share
        // the alphabet — so it is only applied to distinctive scripts.
        if (target == Script.LATIN) return false
        return dominantScript(text) == target
    }

    data class FileVerdict(
        /** Share of judgeable lines already in the target script. */
        val targetShare: Double,
        /** Share of judgeable lines in the configured source script. */
        val sourceShare: Double,
        val judgedLines: Int,
    ) {
        /** Translating this file would damage it rather than improve it. */
        val alreadyTranslated: Boolean get() = judgedLines >= 5 && targetShare >= 0.6

        /** The file is not in the language the app was told to expect. */
        val wrongSource: Boolean get() = judgedLines >= 5 && sourceShare < 0.3 && !alreadyTranslated
    }

    /**
     * The writing system most of a file is in, ignoring the lines that carry
     * too few letters to vote. Used as the second opinion alongside a
     * probabilistic language identifier, because a script is a fact where a
     * detected language is a guess.
     */
    fun dominantScriptOfFile(lines: List<String>): Script {
        val counts = mutableMapOf<Script, Int>()
        var judged = 0

        for (line in lines) {
            val script = dominantScript(line)
            if (script == Script.NONE) continue
            counts[script] = (counts[script] ?: 0) + 1
            judged++
        }

        if (judged < 5) return Script.NONE
        val (best, count) = counts.maxByOrNull { it.value } ?: return Script.NONE
        return if (count * 2 > judged) best else Script.NONE
    }

    /**
     * Judges a whole file from its lines. Called before a run so the user is
     * warned before any damage is done, rather than after.
     */
    fun inspectFile(lines: List<String>, sourceTag: String, targetTag: String): FileVerdict {
        val target = scriptOf(targetTag)
        val source = scriptOf(sourceTag)

        var judged = 0
        var inTarget = 0
        var inSource = 0

        for (line in lines) {
            val script = dominantScript(line)
            if (script == Script.NONE) continue
            judged++
            if (script == target) inTarget++
            if (script == source) inSource++
        }

        if (judged == 0) return FileVerdict(0.0, 0.0, 0)
        return FileVerdict(
            targetShare = inTarget.toDouble() / judged,
            sourceShare = inSource.toDouble() / judged,
            judgedLines = judged,
        )
    }
}
