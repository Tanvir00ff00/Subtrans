package com.subtrans.app.engine

import com.subtrans.app.subtitle.Cue
import com.subtrans.app.subtitle.Subtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/** A find-and-replace the user owns, applied after translation. */
@kotlinx.serialization.Serializable
data class ReplaceRule(
    val find: String,
    val replace: String,
    val regex: Boolean = false,
    val enabled: Boolean = true,
) {
    fun apply(text: String): String = when {
        !enabled || find.isEmpty() -> text
        // This overload takes a replacement *template*, where $ and \ are
        // special — unlike the lambda form used in Markup.restore.
        regex -> runCatching {
            Regex(find).replace(text, Regex.escapeReplacement(replace))
        }.getOrDefault(text)
        else -> text.replace(find, replace)
    }
}

/**
 * A line the offline pass is not confident about, carried with everything a
 * later AI repair needs: the prepared source, the draft, and the placeholder
 * mapping to put styling and names back afterwards.
 */
data class FlaggedLine(
    val cueId: Int,
    val preparedSource: String,
    val draft: String,
    val prepared: TermPrep.Prepared,
    val flags: Set<QualityCheck.Flag>,
)

data class EpisodeReport(
    val total: Int,
    val flagged: List<FlaggedLine>,
    val flagCounts: Map<QualityCheck.Flag, Int>,
) {
    val flaggedShare: Double get() = if (total == 0) 0.0 else flagged.size.toDouble() / total
}

/**
 * Runs a whole episode through the offline engine.
 *
 * Every line is translated here, for free and without a network. What comes
 * out is a short list of lines that look wrong — that list, and nothing else,
 * is what an AI pass is later asked to fix.
 */
class EpisodeTranslator(
    private val engine: TranslationEngine,
    private val glossary: List<GlossaryEntry>,
    private val rules: List<ReplaceRule> = emptyList(),
    private val concurrency: Int = 4,
) {

    suspend fun translate(
        sub: Subtitle,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): EpisodeReport = withContext(Dispatchers.Default) {
        val total = sub.cues.size
        val done = AtomicInteger(0)
        val gate = Semaphore(concurrency.coerceIn(1, 16))

        onProgress(0, total)

        val results = coroutineScope {
            sub.cues.map { cue ->
                async {
                    val outcome = gate.withPermit { translateCue(cue) }
                    onProgress(done.incrementAndGet(), total)
                    outcome
                }
            }.awaitAll()
        }

        val counts = mutableMapOf<QualityCheck.Flag, Int>()
        for (outcome in results) {
            for (flag in outcome.flags) counts[flag] = (counts[flag] ?: 0) + 1
        }

        EpisodeReport(
            total = total,
            flagged = results.filter { it.flags.isNotEmpty() },
            flagCounts = counts,
        )
    }

    private suspend fun translateCue(cue: Cue): FlaggedLine {
        val prepared = TermPrep.prepare(cue.text, glossary)

        val draft = runCatching { engine.translate(prepared.text) }.getOrNull()
        if (draft == null) {
            // A line that would not translate keeps its source text, so the
            // file stays complete and every timestamp stays correct.
            cue.translated = null
            return FlaggedLine(
                cueId = cue.id,
                preparedSource = prepared.text,
                draft = prepared.text,
                prepared = prepared,
                flags = setOf(QualityCheck.Flag.UNCHANGED),
            )
        }

        val finished = TermPrep.finish(draft, prepared)
        cue.translated = rules.fold(finished.text) { acc, rule -> rule.apply(acc) }

        val verdict = QualityCheck.inspect(
            source = prepared.text,
            output = draft,
            targetTag = engine.targetTag,
            lostPlaceholders = finished.lost,
        )
        return FlaggedLine(cue.id, prepared.text, draft, prepared, verdict.flags)
    }

    /**
     * Writes AI repairs back into the episode. Corrections still arrive with
     * placeholders in them, so they go through the same restore path as the
     * offline output — styling and locked names cannot be lost here either.
     */
    fun applyPolish(
        sub: Subtitle,
        flagged: List<FlaggedLine>,
        corrections: Map<Int, String>,
    ): Int {
        if (corrections.isEmpty()) return 0
        val byId = sub.cues.associateBy { it.id }
        var applied = 0

        for (line in flagged) {
            val corrected = corrections[line.cueId] ?: continue
            val cue = byId[line.cueId] ?: continue
            val finished = TermPrep.finish(corrected, line.prepared)
            if (finished.text.isBlank()) continue
            cue.translated = rules.fold(finished.text) { acc, rule -> rule.apply(acc) }
            applied++
        }
        return applied
    }
}
