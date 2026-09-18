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
    /** Lines that never reached the model: already translated, or letterless. */
    val skipped: Int = 0,
    /** Calls actually made, against [total]. Lower is faster. */
    val modelCalls: Int = 0,
) {
    val flaggedShare: Double get() = if (total == 0) 0.0 else flagged.size.toDouble() / total
}

/**
 * Runs a whole episode through the offline engine.
 *
 * The work is arranged to make as few calls into the model as possible, since
 * the per-call overhead — not the translation itself — is what makes a season
 * take hours. Lines that need nothing are dropped, identical lines are
 * translated once, and the rest ride several to a call. See [Batching].
 */
class EpisodeTranslator(
    private val engine: TranslationEngine,
    private val glossary: List<GlossaryEntry>,
    private val rules: List<ReplaceRule> = emptyList(),
    private val concurrency: Int = 4,
    private val batchLines: Int = 8,
    /** Re-wrap translated lines at this width; 0 leaves them on one line. */
    private val wrapWidth: Int = 42,
) {

    suspend fun translate(
        sub: Subtitle,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): EpisodeReport = withContext(Dispatchers.Default) {
        val total = sub.cues.size
        onProgress(0, total)
        if (total == 0) return@withContext EpisodeReport(0, emptyList(), emptyMap())

        val prepared = sub.cues.map { TermPrep.prepare(it.text, glossary) }

        // Where a cue wraps across two display lines carries no meaning, so the
        // break is collapsed before translating. That lets the cue share a call
        // with others and hands the model a whole sentence rather than half of
        // one; the result is re-wrapped afterwards.
        val forModel = prepared.map { Batching.flatten(it.text) }

        // Which cues actually have to reach the model.
        val needsWork = sub.cues.indices.filter { i ->
            !LanguageGuard.alreadyInTarget(sub.cues[i].text, engine.targetTag) &&
                Batching.needsTranslation(forModel[i])
        }

        // Identical lines are translated once. Subtitles repeat themselves far
        // more than they look like they do.
        val uniqueTexts = mutableListOf<String>()
        val indexOfText = mutableMapOf<String, Int>()
        val cueToUnique = mutableMapOf<Int, Int>()
        for (i in needsWork) {
            val text = forModel[i]
            val slot = indexOfText.getOrPut(text) {
                uniqueTexts += text
                uniqueTexts.size - 1
            }
            cueToUnique[i] = slot
        }

        val cuesPerUnique = IntArray(uniqueTexts.size)
        for (slot in cueToUnique.values) cuesPerUnique[slot]++

        val results = arrayOfNulls<String>(uniqueTexts.size)
        val calls = AtomicInteger(0)
        val done = AtomicInteger(total - needsWork.size)
        onProgress(done.get(), total)

        val plan = Batching.plan(uniqueTexts, maxLines = batchLines)
        val gate = Semaphore(concurrency.coerceIn(1, 16))

        coroutineScope {
            plan.batches.map { batch ->
                async {
                    gate.withPermit { runBatch(batch, uniqueTexts, results, calls) }
                    var advanced = 0
                    for (slot in batch) advanced += cuesPerUnique[slot]
                    onProgress(done.addAndGet(advanced), total)
                }
            }.awaitAll()
        }

        // Stitch everything back onto the cues and judge the result.
        val flagged = mutableListOf<FlaggedLine>()
        val counts = mutableMapOf<QualityCheck.Flag, Int>()

        for (i in sub.cues.indices) {
            val cue = sub.cues[i]
            val slot = cueToUnique[i]
            if (slot == null) {
                // Nothing to do: already in the target language, or letterless.
                cue.translated = null
                continue
            }

            val draft = results[slot]
            if (draft == null) {
                cue.translated = null
                flagged += FlaggedLine(
                    cue.id, forModel[i], forModel[i], prepared[i],
                    setOf(QualityCheck.Flag.UNCHANGED),
                )
                counts[QualityCheck.Flag.UNCHANGED] =
                    (counts[QualityCheck.Flag.UNCHANGED] ?: 0) + 1
                continue
            }

            val finished = TermPrep.finish(draft, prepared[i])
            val ruled = rules.fold(finished.text) { acc, rule -> rule.apply(acc) }
            cue.translated = Batching.rewrap(ruled, wrapWidth)

            val verdict = QualityCheck.inspect(
                source = forModel[i],
                output = draft,
                targetTag = engine.targetTag,
                lostPlaceholders = finished.lost,
            )
            if (verdict.flags.isNotEmpty()) {
                flagged += FlaggedLine(cue.id, forModel[i], draft, prepared[i], verdict.flags)
                for (flag in verdict.flags) counts[flag] = (counts[flag] ?: 0) + 1
            }
        }

        onProgress(total, total)
        EpisodeReport(
            total = total,
            flagged = flagged,
            flagCounts = counts,
            skipped = total - needsWork.size,
            modelCalls = calls.get(),
        )
    }

    /**
     * Translates one batch, verifying the line count before accepting it. A
     * mismatched result is not repaired or guessed at — the batch is redone one
     * line at a time, because a shifted line is worse than a slow one.
     */
    private suspend fun runBatch(
        batch: List<Int>,
        texts: List<String>,
        results: Array<String?>,
        calls: AtomicInteger,
    ) {
        val slice = batch.map { texts[it] }

        if (batch.size > 1) {
            val joined = Batching.join(slice)
            val output = runCatching {
                calls.incrementAndGet()
                engine.translate(joined)
            }.getOrNull()

            val parts = output?.let { Batching.split(it, batch.size) }
            if (parts != null) {
                batch.forEachIndexed { at, slot -> results[slot] = parts[at] }
                return
            }
        }

        for (slot in batch) {
            results[slot] = runCatching {
                calls.incrementAndGet()
                engine.translate(texts[slot])
            }.getOrNull()
        }
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
            val ruled = rules.fold(finished.text) { acc, rule -> rule.apply(acc) }
            cue.translated = Batching.rewrap(ruled, wrapWidth)
            applied++
        }
        return applied
    }
}
