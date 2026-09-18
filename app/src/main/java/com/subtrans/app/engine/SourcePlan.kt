package com.subtrans.app.engine

/**
 * Deciding what to do with each file in a mixed batch.
 *
 * A dropped folder or archive is rarely uniform. Some files are English, some
 * Hindi, and a few are already the language being translated into — often
 * because they were translated on an earlier run and saved back alongside the
 * originals. Three rules follow from that, and all three exist because getting
 * any of them wrong loses the user's work:
 *
 *   1. The source language is decided per file, not once for the batch.
 *   2. A file already in the target language is passed through untouched — it
 *      still belongs in the output. Skipping it would quietly hand back 95
 *      files for 100 given, and the missing five would be exactly the ones
 *      already finished.
 *   3. A file whose language cannot be translated is reported, not dropped.
 *
 * The detection itself needs ML Kit and a device; everything here is pure so
 * the decisions can be tested without one.
 */
object SourcePlan {

    sealed interface Decision {
        /** Translate this file, reading it as [sourceTag]. */
        data class Translate(val sourceTag: String, val confident: Boolean) : Decision

        /** Already in the target language: copy it to the output unchanged. */
        data object PassThrough : Decision

        /** Nothing sensible can be done; the reason is shown to the user. */
        data class Unsupported(val sourceTag: String?) : Decision
    }

    /**
     * @param detected what the language identifier said, or null when it had
     *   no opinion. "und" is treated the same as null.
     * @param script what the writing system says, as a cheap second opinion.
     * @param configured the language the user picked in settings, used as the
     *   last resort so a file is never refused merely for being hard to read.
     * @param supports whether the offline engine has a model for a language.
     */
    fun decide(
        detected: String?,
        script: LanguageGuard.Script,
        configured: String,
        target: String,
        supports: (String) -> Boolean,
    ): Decision {
        val targetScript = LanguageGuard.scriptOf(target)
        val clean = detected?.takeIf { it.isNotBlank() && it != "und" }?.lowercase()?.substringBefore('-')

        // The script is the stronger signal for "already done": a Bengali file
        // is Bengali whatever a probabilistic identifier thinks, and rewriting
        // it would corrupt it.
        if (script == targetScript && targetScript != LanguageGuard.Script.LATIN) {
            return Decision.PassThrough
        }
        if (clean == target.lowercase().substringBefore('-')) return Decision.PassThrough

        if (clean != null && supports(clean)) return Decision.Translate(clean, confident = true)

        // The identifier failed or named a language the engine cannot handle.
        // Falling back to the configured source is better than refusing, but
        // the caller is told the choice was a guess.
        if (clean != null && !supports(clean)) return Decision.Unsupported(clean)

        if (supports(configured)) return Decision.Translate(configured, confident = false)
        return Decision.Unsupported(null)
    }

    /** Files are grouped by source language so each model is loaded once. */
    fun <T> groupBySource(items: List<T>, sourceOf: (T) -> String): Map<String, List<T>> =
        items.groupBy(sourceOf)
}
