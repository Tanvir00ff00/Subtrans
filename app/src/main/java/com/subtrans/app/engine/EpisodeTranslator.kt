package com.subtrans.app.engine

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
 * later AI repair needs: the prepared source, the draft, and the token mapping
 * to put locked names back afterwards.
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
    /** Lines the offline retry rescued without spending an AI call. */
    val repaired: Int = 0,
    /** Lines where inline emphasis was dropped to keep the sentence whole. */
    val stylingDropped: Int = 0,
    /** Lines answered from [Lexicon] rather than by the model. */
    val fromTable: Int = 0,
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
 *
 * A cue is no longer one unit of translation. [Markup] cuts styling out of the
 * line first, so a cue becomes one or more *chunks* of pure words; batching,
 * de-duplication and retries all work on chunks, and the cue is reassembled at
 * the end. Almost every real line yields exactly one chunk, so this costs
 * nothing and buys the guarantee that no tag is ever shown to the model.
 */
class EpisodeTranslator(
    private val engine: TranslationEngine,
    private val glossary: List<GlossaryEntry>,
    private val rules: List<ReplaceRule> = emptyList(),
    private val concurrency: Int = 4,
    private val batchLines: Int = 8,
    /** Re-wrap translated lines at this width; 0 leaves them on one line. */
    private val wrapWidth: Int = 42,
    /** Answer known short lines from [Lexicon] instead of asking the model. */
    private val useLexicon: Boolean = true,
) {

    suspend fun translate(
        sub: Subtitle,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): EpisodeReport = withContext(Dispatchers.Default) {
        val total = sub.cues.size
        onProgress(0, total)
        if (total == 0) return@withContext EpisodeReport(0, emptyList(), emptyMap())

        val prepared = sub.cues.map { TermPrep.prepare(it.text, glossary) }

        // A cue is skipped whole when it is already written in the target
        // language — translating Bengali as if it were English is the failure
        // that LanguageGuard exists to stop.
        val skipCue = sub.cues.map { LanguageGuard.alreadyInTarget(it.text, engine.targetTag) }

        // Flatten every chunk into the one-line form the model sees. Where a
        // cue wraps across two display lines is a layout decision, not a
        // meaning one, and the model does better with a whole sentence.
        val owner = mutableListOf<Int>()
        val chunkText = mutableListOf<String>()
        for (i in sub.cues.indices) {
            for (chunk in prepared[i].chunks) {
                owner += i
                chunkText += Batching.flatten(chunk)
            }
        }

        val translatedChunk = arrayOfNulls<String>(chunkText.size)

        // Lines the model is known to get wrong are answered from a table
        // before it ever sees them. "Fine." is not a monetary penalty and
        // "Bye-bye!" is not sleep, however confidently the model says so.
        var fromTable = 0
        if (useLexicon) {
            for (k in chunkText.indices) {
                if (skipCue[owner[k]]) continue
                val known = Lexicon.lookup(chunkText[k], engine.targetTag) ?: continue
                translatedChunk[k] = known
                fromTable++
            }
        }

        val needsWork = chunkText.indices.filter { k ->
            !skipCue[owner[k]] &&
                translatedChunk[k] == null &&
                Batching.needsTranslation(chunkText[k])
        }
        val calls = AtomicInteger(0)
        val gate = Semaphore(concurrency.coerceIn(1, 16))

        runPass(needsWork, chunkText, translatedChunk, calls, gate) { done ->
            onProgress(progressOf(done, needsWork.size, total), total)
        }

        // Stitch, judge, and give anything suspicious one free offline retry
        // with the locked names written out in full. A model that choked on a
        // token usually does not choke on the plain sentence, and an
        // inconsistent name beats an English line in a Bengali file.
        val stitched = sub.cues.indices.map { i ->
            stitch(i, sub, prepared[i], skipCue[i], owner, chunkText, translatedChunk)
        }

        val retryable = stitched.withIndex()
            .filter { (_, r) -> r != null && QualityCheck.worthRetrying(r.verdict.flags) }
            .map { it.index }

        val repaired: Map<Int, String> =
            if (retryable.isEmpty()) emptyMap() else retry(retryable, prepared, stitched, calls, gate)

        val flagged = mutableListOf<FlaggedLine>()
        val counts = mutableMapOf<QualityCheck.Flag, Int>()
        var skippedLines = 0
        var stylingDropped = 0

        for (i in sub.cues.indices) {
            val cue = sub.cues[i]
            val result = stitched[i]
            if (prepared[i].stylingDropped) stylingDropped++

            if (result == null) {
                cue.translated = null
                skippedLines++
                continue
            }

            // A line the retry rescued is finished: it must not be overwritten
            // by the draft it replaced, and it must not be sent to the AI.
            val rescued = repaired[i]
            if (rescued != null) {
                cue.translated = Batching.rewrap(rescued, wrapWidth)
                continue
            }

            cue.translated = Batching.rewrap(result.text, wrapWidth)
            if (result.verdict.flags.isNotEmpty()) {
                flagged += FlaggedLine(
                    cueId = cue.id,
                    preparedSource = result.source,
                    draft = result.draft,
                    prepared = prepared[i],
                    flags = result.verdict.flags,
                )
                for (flag in result.verdict.flags) counts[flag] = (counts[flag] ?: 0) + 1
            }
        }

        onProgress(total, total)
        EpisodeReport(
            total = total,
            flagged = flagged,
            flagCounts = counts,
            skipped = skippedLines,
            modelCalls = calls.get(),
            repaired = repaired.size,
            stylingDropped = stylingDropped,
            fromTable = fromTable,
        )
    }

    private fun progressOf(doneChunks: Int, totalChunks: Int, totalCues: Int): Int {
        if (totalChunks == 0) return totalCues
        return (doneChunks.toLong() * totalCues / totalChunks).toInt().coerceIn(0, totalCues)
    }

    /** Translates a set of chunks, de-duplicated and batched. */
    private suspend fun runPass(
        indices: List<Int>,
        texts: List<String>,
        into: Array<String?>,
        calls: AtomicInteger,
        gate: Semaphore,
        onProgress: (done: Int) -> Unit,
    ) {
        if (indices.isEmpty()) return

        // Identical lines are translated once. Subtitles repeat themselves far
        // more than they look like they do.
        val unique = mutableListOf<String>()
        val slotOfText = mutableMapOf<String, Int>()
        val slotOf = mutableMapOf<Int, Int>()
        for (k in indices) {
            val slot = slotOfText.getOrPut(texts[k]) {
                unique += texts[k]
                unique.size - 1
            }
            slotOf[k] = slot
        }

        val usesPerSlot = IntArray(unique.size)
        for (slot in slotOf.values) usesPerSlot[slot]++

        val results = arrayOfNulls<String>(unique.size)
        val plan = Batching.plan(unique, maxLines = batchLines)
        val done = AtomicInteger(0)

        coroutineScope {
            plan.batches.map { batch ->
                async {
                    gate.withPermit { runBatch(batch, unique, results, calls) }
                    var advanced = 0
                    for (slot in batch) advanced += usesPerSlot[slot]
                    onProgress(done.addAndGet(advanced))
                }
            }.awaitAll()
        }

        for (k in indices) into[k] = results[slotOf.getValue(k)]
    }

    private data class Stitched(
        val text: String,
        val source: String,
        val draft: String,
        val verdict: QualityCheck.Verdict,
    )

    /** Rebuilds one cue from its translated chunks and judges the result. */
    private fun stitch(
        cue: Int,
        sub: Subtitle,
        prepared: TermPrep.Prepared,
        skipped: Boolean,
        owner: List<Int>,
        chunkText: List<String>,
        translated: Array<String?>,
    ): Stitched? {
        if (skipped) return null

        val slots = owner.indices.filter { owner[it] == cue }
        if (slots.isEmpty()) return null
        // A cue whose every chunk was letterless had nothing to translate.
        if (slots.none { translated[it] != null }) return null

        val drafts = slots.map { translated[it] ?: chunkText[it] }
        val sources = slots.map { chunkText[it] }

        val finished = TermPrep.finish(drafts, prepared)
        val ruled = rules.fold(finished.text) { acc, rule -> rule.apply(acc) }

        val verdict = QualityCheck.inspect(
            source = sources.joinToString(" "),
            output = drafts.joinToString(" "),
            targetTag = engine.targetTag,
            lostPlaceholders = finished.lost,
        )
        return Stitched(ruled, sources.joinToString(" "), drafts.joinToString(" "), verdict)
    }

    /**
     * One more offline attempt at the lines that came back wrong. Two things
     * change on the second try, and either can be the thing that was breaking
     * it:
     *
     *  - **The line goes over alone.** Most lines ride to the model eight to a
     *    call, and a neighbour can drag a translation off course or come back
     *    merged. On its own, a line has nothing to be confused by.
     *  - **Locked names are written out in full.** If a token is what the model
     *    choked on, there is no token left to choke on. The name may then be
     *    spelled inconsistently, which is a real cost — but a readable line
     *    with an odd spelling beats an English line in a Bengali file.
     *
     * Only lines already judged broken get this, so it costs a handful of
     * calls, and the result is kept only when it is measurably less broken.
     * Returns the cue indices it rescued, with their finished text.
     */
    private suspend fun retry(
        cues: List<Int>,
        prepared: List<TermPrep.Prepared>,
        stitched: List<Stitched?>,
        calls: AtomicInteger,
        gate: Semaphore,
    ): Map<Int, String> = coroutineScope {
        cues.map { i ->
            async {
                val before = stitched[i]?.verdict?.flags ?: return@async null
                val plain = prepared[i].plainChunks().map { Batching.flatten(it) }
                if (plain.none { Batching.needsTranslation(it) }) return@async null

                val out = plain.map { chunk ->
                    if (!Batching.needsTranslation(chunk)) chunk else {
                        gate.withPermit {
                            runCatching {
                                calls.incrementAndGet()
                                engine.translate(chunk)
                            }.getOrNull()
                        } ?: return@async null
                    }
                }

                val verdict = QualityCheck.inspect(
                    source = plain.joinToString(" "),
                    output = out.joinToString(" "),
                    targetTag = engine.targetTag,
                    lostPlaceholders = 0,
                )
                if (!QualityCheck.isBetter(verdict.flags, before)) return@async null

                // The retry carried no tokens, so nothing needs restoring —
                // only the markup goes back around it.
                val layout = Markup.Layout(prepared[i].slots, plain, prepared[i].stylingDropped)
                val text = rules.fold(Markup.assemble(layout, out).trim()) { acc, rule ->
                    rule.apply(acc)
                }
                i to text
            }
        }.awaitAll().filterNotNull().toMap()
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
     * tokens in them, so they go through the same restore path as the offline
     * output — locked names cannot be lost here either.
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
            val finished = TermPrep.finishWhole(corrected, line.prepared)
            if (finished.text.isBlank()) continue
            val ruled = rules.fold(finished.text) { acc, rule -> rule.apply(acc) }
            cue.translated = Batching.rewrap(ruled, wrapWidth)
            applied++
        }
        return applied
    }
}
