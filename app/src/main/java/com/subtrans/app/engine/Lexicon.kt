package com.subtrans.app.engine

/**
 * A short list of lines the offline model gets reliably, embarrassingly wrong.
 *
 * A general translation model has no idea it is reading dialogue, so it treats
 * a one-word line as prose and picks the commonest sense of the word. Measured
 * on seven real episodes, that produced:
 *
 *   "Fine."      -> জরিমানা          (a monetary fine)
 *   "I'm fine."  -> আমি জরিমানা করছি  ("I am issuing a fine")
 *   "Bye-bye!"   -> ঘুম!             (sleep)
 *   "Damn it!"   -> এটা!             (this)
 *   "Huh?"       -> তাই না?           ("isn't it?" — the opposite of a question)
 *
 * Subtitles are full of these: across six episodes "Huh?" appeared 17 times and
 * "No way" 22. They are also trivially fixable, because they are whole lines
 * with one obvious spoken meaning. So they are answered from a table instead of
 * from the model — which is both more accurate and one fewer call.
 *
 * Three deliberate limits:
 *
 *  - **Whole lines only.** "Fine." is answered; "Fine, let's go" is not,
 *    because the word is doing different work there and a table cannot tell.
 *  - **The user wins.** A glossary entry covering the same line has already
 *    been turned into a token by [TermPrep], so nothing here can match it, and
 *    replace rules run afterwards. This is a floor, not a ceiling.
 *  - **Seeded, not authoritative.** These are the renderings a Bengali speaker
 *    would use for anime dialogue, not the only ones. They are meant to be
 *    read and corrected.
 */
object Lexicon {

    /**
     * Keyed on the line with case, surrounding space and trailing punctuation
     * removed. Values carry no terminal punctuation either — [lookup] puts the
     * original ending back, so one entry covers "Huh?", "Huh?!" and "Huh...".
     */
    private val BENGALI: Map<String, String> = mapOf(
        // Agreement, refusal, acknowledgement
        "yeah" to "হ্যাঁ",
        "yes" to "হ্যাঁ",
        "yes, ma'am" to "জি, ম্যাডাম",
        "yes, sir" to "জি, স্যার",
        "no" to "না",
        "no way" to "অসম্ভব",
        "impossible" to "অসম্ভব",
        "of course" to "অবশ্যই",
        "okay" to "ঠিক আছে",
        "ok" to "ঠিক আছে",
        "fine" to "ঠিক আছে",
        "all right" to "ঠিক আছে",
        "alright" to "ঠিক আছে",
        "i'm fine" to "আমি ঠিক আছি",
        "i am fine" to "আমি ঠিক আছি",
        "got it" to "বুঝেছি",
        "understood" to "বুঝেছি",
        "i see" to "বুঝলাম",
        "me too" to "আমিও",

        // Questions and reactions
        "huh" to "অ্যাঁ",
        "what" to "কী",
        "why" to "কেন",
        "really" to "সত্যিই",
        "who are you" to "তুমি কে",
        "are you okay" to "তুমি ঠিক আছ",
        "are you all right" to "তুমি ঠিক আছ",
        "what's going on" to "কী হচ্ছে",
        "what happened" to "কী হয়েছে",
        "what's the matter" to "কী হয়েছে",

        // Calls and commands
        "hey" to "এই",
        "wait" to "দাঁড়াও",
        "hold on" to "দাঁড়াও",
        "stop" to "থামো",
        "don't do it" to "কোরো না",
        "hurry" to "তাড়াতাড়ি",
        "let's go" to "চলো",
        "shut up" to "চুপ করো",
        "damn it" to "ধুর ছাই",
        "everyone" to "সবাই",

        // Courtesies and farewells
        "thank you" to "ধন্যবাদ",
        "thanks" to "ধন্যবাদ",
        "sorry" to "দুঃখিত",
        "i'm sorry" to "আমি দুঃখিত",
        "excuse me" to "শুনুন",
        "good morning" to "সুপ্রভাত",
        "goodbye" to "বিদায়",
        "farewell" to "বিদায়",
        "bye-bye" to "টা-টা",
        "take care" to "সাবধানে থেকো",
        "see you later" to "পরে দেখা হবে",
        "see you" to "দেখা হবে",

        // Screen text that is not a proper noun
        "hospital" to "হাসপাতাল",
        "explode" to "বিস্ফোরণ",
        "thunder" to "বজ্রপাত",
        "aviary" to "পক্ষিশালা",
    )

    private val TABLES: Map<String, Map<String, String>> = mapOf(
        "bn" to BENGALI,
    )

    private val TRAILING = Regex("""[.!?…]+$""")
    private val SPACES = Regex("""\s+""")

    /** Whether anything is known for this target language at all. */
    fun covers(targetTag: String): Boolean = table(targetTag) != null

    private fun table(targetTag: String): Map<String, String>? =
        TABLES[targetTag.lowercase().substringBefore('-')]

    /**
     * The agreed rendering of a whole line, or null to let the model handle it.
     *
     * The line's own ending is kept: a full stop becomes a dari, an exclamation
     * or question mark is carried over as-is, and an ellipsis stays an ellipsis
     * rather than turning into a row of dari.
     */
    fun lookup(line: String, targetTag: String): String? {
        val words = table(targetTag) ?: return null

        val trimmed = line.trim().replace(SPACES, " ")
        if (trimmed.isEmpty()) return null

        val tail = TRAILING.find(trimmed)?.value ?: ""
        val base = trimmed.removeSuffix(tail).trim().lowercase()
        if (base.isEmpty()) return null

        val hit = words[base] ?: return null
        return hit + ending(tail)
    }

    /** How a line's ending is written in Bengali. */
    private fun ending(tail: String): String = when {
        tail.isEmpty() -> ""
        // "..." is a pause in any script; "।।।" is nonsense.
        tail.all { it == '.' } && tail.length >= 2 -> "..."
        tail == "…" -> "…"
        else -> tail.replace(".", "।")
    }

    /** Every line the table can answer, for showing the user what it covers. */
    fun entries(targetTag: String): List<GlossaryEntry> =
        table(targetTag)?.map { (k, v) -> GlossaryEntry(k, v) }?.sortedBy { it.source } ?: emptyList()
}
