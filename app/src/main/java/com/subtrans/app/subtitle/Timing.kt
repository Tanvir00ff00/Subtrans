package com.subtrans.app.subtitle

/**
 * Shifting a subtitle in time.
 *
 * Downloaded subtitles are routinely cut for a different release of the same
 * episode and land a second or two early or late. Rather than throwing the file
 * away, the whole track can be nudged — every timestamp moves by the same
 * offset, so the dialogue stays in step with itself.
 */

private val SRT_STAMP = Regex("""(-?)(\d{1,3}):(\d{2}):(\d{2})[,.](\d{1,3})""")
private val ASS_STAMP = Regex("""(-?)(\d{1,2}):(\d{2}):(\d{2})\.(\d{2})""")

/** A copy of [sub] with every timestamp moved by [offsetMs]. */
fun shifted(sub: Subtitle, offsetMs: Long): Subtitle {
    if (offsetMs == 0L) return sub

    val cues = sub.cues.map { cue ->
        val meta = when (val m = cue.meta) {
            is CueMeta.Srt -> m.copy(timing = shiftLine(m.timing, offsetMs, srt = true))
            is CueMeta.Vtt -> m.copy(timing = shiftLine(m.timing, offsetMs, srt = true))
            is CueMeta.Ass -> m.copy(prefix = shiftAssPrefix(m.prefix, offsetMs))
        }
        Cue(cue.id, cue.text, meta).also { it.translated = cue.translated }
    }

    // ASS keeps its Dialogue lines in the scaffold too; serialize() rewrites
    // them from the cue prefixes, so the scaffold needs no separate pass.
    return Subtitle(sub.format, cues, sub.scaffold, sub.fileName)
}

private fun shiftLine(timing: String, offsetMs: Long, srt: Boolean): String =
    if (srt) SRT_STAMP.replace(timing) { shiftMatch(it, offsetMs, millisDigits = 3) } else timing

private fun shiftAssPrefix(prefix: String, offsetMs: Long): String =
    ASS_STAMP.replace(prefix) { shiftMatch(it, offsetMs, millisDigits = 2) }

private fun shiftMatch(match: MatchResult, offsetMs: Long, millisDigits: Int): String {
    val (sign, h, m, s, frac) = match.destructured
    val scale = if (millisDigits == 2) 10L else 1L
    val base = h.toLong() * 3_600_000 + m.toLong() * 60_000 + s.toLong() * 1_000 + frac.padEnd(3, '0').take(3).toLong()
    val signed = if (sign == "-") -base else base

    // A track can never start before zero; clamp rather than wrap into
    // negative timestamps that players reject outright.
    val moved = (signed + offsetMs).coerceAtLeast(0L)

    val hours = moved / 3_600_000
    val minutes = (moved % 3_600_000) / 60_000
    val seconds = (moved % 60_000) / 1_000
    val millis = moved % 1_000

    return if (millisDigits == 2) {
        "%d:%02d:%02d.%02d".format(hours, minutes, seconds, millis / scale)
    } else {
        "%02d:%02d:%02d,%03d".format(hours, minutes, seconds, millis)
    }
}

/**
 * Removes cues with no text and merges a cue that repeats the line before it,
 * which some rippers emit for every frame of a held caption.
 */
fun tidied(sub: Subtitle): Pair<Subtitle, Int> {
    val kept = mutableListOf<Cue>()
    var removed = 0
    var previousText: String? = null

    for (cue in sub.cues) {
        val text = cue.output.trim()
        if (text.isEmpty()) {
            removed++
            continue
        }
        if (text == previousText) {
            removed++
            continue
        }
        previousText = text
        kept += Cue(kept.size, cue.text, cue.meta).also { it.translated = cue.translated }
    }

    if (removed == 0) return sub to 0
    return Subtitle(sub.format, kept, sub.scaffold, sub.fileName) to removed
}

/**
 * Renders the file with the source line above each translation, which is how
 * people watch a show while still learning the original language.
 */
fun serializeBilingual(sub: Subtitle): String {
    val doubled = sub.cues.map { cue ->
        val translated = cue.translated
        val both = if (translated.isNullOrBlank() || translated == cue.text) {
            cue.text
        } else {
            "$translated\n${cue.text}"
        }
        Cue(cue.id, cue.text, cue.meta).also { it.translated = both }
    }
    return serialize(Subtitle(sub.format, doubled, sub.scaffold, sub.fileName))
}
