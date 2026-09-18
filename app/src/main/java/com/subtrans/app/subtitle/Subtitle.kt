package com.subtrans.app.subtitle

/**
 * SRT / WebVTT / ASS-SSA parsing and serialisation.
 *
 * The golden rule of this file: timestamps and structure never reach the
 * translation engine. It is handed [Cue.text] and writes [Cue.translated] back
 * into the same slot, so a subtitle cannot drift out of sync no matter what
 * comes back.
 */

enum class SubFormat { SRT, VTT, ASS }

sealed interface CueMeta {
    data class Srt(val index: String, val timing: String) : CueMeta
    data class Vtt(val id: String?, val timing: String) : CueMeta
    data class Ass(val lineIndex: Int, val prefix: String) : CueMeta
}

class Cue(
    /** Stable index into [Subtitle.cues]; also the id the engine works with. */
    val id: Int,
    /** Original untouched text, including any markup. */
    val text: String,
    /** Format-specific payload used to rebuild the file faithfully. */
    val meta: CueMeta,
) {
    /** Filled in once translated; null means "left in the source language". */
    var translated: String? = null

    val output: String get() = translated ?: text
}

class Subtitle(
    val format: SubFormat,
    val cues: List<Cue>,
    /** Everything [serialize] needs that is not a cue. */
    val scaffold: List<String>,
    val fileName: String,
)

private val SRT_TIMING =
    Regex("""^\s*-?\d{1,3}:\d{2}:\d{2}[,.]\d{1,3}\s*-->\s*-?\d{1,3}:\d{2}:\d{2}[,.]\d{1,3}""")

private const val BOM = '\uFEFF'

fun detectFormat(fileName: String, content: String): SubFormat {
    when (fileName.substringAfterLast('.', "").lowercase()) {
        "ass", "ssa" -> return SubFormat.ASS
        "vtt" -> return SubFormat.VTT
        "srt" -> return SubFormat.SRT
    }
    // Fall back to sniffing: mis-named files are common in subtitle packs.
    if (Regex("""^\s*WEBVTT""").containsMatchIn(content)) return SubFormat.VTT
    if (Regex("""^\s*\[Script Info\]""", RegexOption.IGNORE_CASE).containsMatchIn(content)) {
        return SubFormat.ASS
    }
    return SubFormat.SRT
}

fun parse(fileName: String, raw: String): Subtitle {
    val content = raw.trimStart(BOM).replace("\r\n", "\n").replace("\r", "\n")
    return when (detectFormat(fileName, content)) {
        SubFormat.ASS -> parseAss(fileName, content)
        SubFormat.VTT -> parseVtt(fileName, content)
        SubFormat.SRT -> parseSrt(fileName, content)
    }
}

/* ------------------------------------------------------------------ SRT */

private fun parseSrt(fileName: String, content: String): Subtitle {
    val cues = mutableListOf<Cue>()

    for (block in content.split(Regex("\n{2,}"))) {
        val lines = block.split("\n").dropWhile { it.isBlank() }
        if (lines.isEmpty()) continue

        var cursor = 0
        var index = ""
        if (!SRT_TIMING.containsMatchIn(lines[0]) &&
            lines.size > 1 &&
            SRT_TIMING.containsMatchIn(lines[1])
        ) {
            index = lines[0].trim()
            cursor = 1
        }

        val timing = lines.getOrNull(cursor) ?: continue
        if (!SRT_TIMING.containsMatchIn(timing)) continue

        val text = lines.drop(cursor + 1).joinToString("\n").trim()
        if (text.isEmpty()) continue

        cues += Cue(
            id = cues.size,
            text = text,
            meta = CueMeta.Srt(
                index = index.ifEmpty { (cues.size + 1).toString() },
                timing = timing.trim(),
            ),
        )
    }

    return Subtitle(SubFormat.SRT, cues, emptyList(), fileName)
}

/* ------------------------------------------------------------------ VTT */

private fun parseVtt(fileName: String, content: String): Subtitle {
    val cues = mutableListOf<Cue>()
    val scaffold = mutableListOf<String>()

    for (block in content.split(Regex("\n{2,}"))) {
        val lines = block.split("\n").filter { it.isNotBlank() }
        if (lines.isEmpty()) continue

        val head = lines[0].trim()
        // The WEBVTT header and NOTE / STYLE / REGION blocks pass through.
        if (head.startsWith("WEBVTT") ||
            Regex("""^(NOTE|STYLE|REGION)\b""").containsMatchIn(head)
        ) {
            scaffold += block.trim()
            continue
        }

        var cursor = 0
        var id: String? = null
        if (!head.contains("-->") && lines.size > 1 && lines[1].contains("-->")) {
            id = head
            cursor = 1
        }

        val timing = lines.getOrNull(cursor) ?: continue
        if (!timing.contains("-->")) continue

        val text = lines.drop(cursor + 1).joinToString("\n").trim()
        if (text.isEmpty()) continue

        cues += Cue(cues.size, text, CueMeta.Vtt(id, timing.trim()))
    }

    if (scaffold.isEmpty()) scaffold += "WEBVTT"
    return Subtitle(SubFormat.VTT, cues, scaffold, fileName)
}

/* ------------------------------------------------------------------ ASS */

private fun parseAss(fileName: String, content: String): Subtitle {
    val lines = content.split("\n")
    val cues = mutableListOf<Cue>()

    // The Format: line inside [Events] says which comma-separated field holds
    // the text. It is always last, but its position varies between files.
    var textFieldIndex = 9
    var inEvents = false

    lines.forEachIndexed { lineIndex, line ->
        val trimmed = line.trim()

        if (Regex("""^\[.*\]$""").matches(trimmed)) {
            inEvents = trimmed.equals("[Events]", ignoreCase = true)
            return@forEachIndexed
        }

        if (inEvents && Regex("""^Format\s*:""", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) {
            val fields = trimmed
                .replaceFirst(Regex("""^Format\s*:""", RegexOption.IGNORE_CASE), "")
                .split(",")
                .map { it.trim().lowercase() }
            val idx = fields.indexOf("text")
            if (idx >= 0) textFieldIndex = idx
            return@forEachIndexed
        }

        if (!inEvents) return@forEachIndexed
        if (!Regex("""^Dialogue\s*:""", RegexOption.IGNORE_CASE).containsMatchIn(trimmed)) {
            return@forEachIndexed
        }

        val body = line.replaceFirst(Regex("""^(\s*Dialogue\s*:)""", RegexOption.IGNORE_CASE), "")
        val head = line.dropLast(body.length)
        // Split on the first `textFieldIndex` commas only — the text itself may
        // contain any number of them.
        val parts = splitN(body, ',', textFieldIndex)
        if (parts.size <= textFieldIndex) return@forEachIndexed

        val text = parts[textFieldIndex]
        if (text.isBlank()) return@forEachIndexed

        val prefix = head + parts.take(textFieldIndex).joinToString(",") + ","
        cues += Cue(cues.size, text.trim(), CueMeta.Ass(lineIndex, prefix))
    }

    return Subtitle(SubFormat.ASS, cues, lines, fileName)
}

private fun splitN(input: String, sep: Char, n: Int): List<String> {
    val out = mutableListOf<String>()
    var rest = input
    repeat(n) {
        val at = rest.indexOf(sep)
        if (at == -1) {
            out += rest
            return out
        }
        out += rest.substring(0, at)
        rest = rest.substring(at + 1)
    }
    out += rest
    return out
}

/* ------------------------------------------------------------ serialise */

fun serialize(sub: Subtitle): String = when (sub.format) {
    SubFormat.SRT -> sub.cues
        .mapIndexed { i, cue ->
            val m = cue.meta as CueMeta.Srt
            "${i + 1}\n${m.timing}\n${cue.output}"
        }
        .joinToString("\n\n") + "\n"

    SubFormat.VTT -> {
        val head = sub.scaffold.joinToString("\n\n")
        val body = sub.cues.joinToString("\n\n") { cue ->
            val m = cue.meta as CueMeta.Vtt
            (m.id?.let { "$it\n" } ?: "") + m.timing + "\n" + cue.output
        }
        "$head\n\n$body\n"
    }

    // Rebuild the original file and swap only the Dialogue text fields.
    SubFormat.ASS -> {
        val lines = sub.scaffold.toMutableList()
        for (cue in sub.cues) {
            val m = cue.meta as CueMeta.Ass
            lines[m.lineIndex] = m.prefix + cue.output.replace("\n", "\\N")
        }
        lines.joinToString("\n")
    }
}

/**
 * The cue's start time, formatted for reading rather than for a file.
 * `00:01:23,480 --> 00:01:26,000` becomes `1:23`.
 */
val Cue.timeLabel: String
    get() {
        val raw = when (val m = meta) {
            is CueMeta.Srt -> m.timing.substringBefore("-->").trim()
            is CueMeta.Vtt -> m.timing.substringBefore("-->").trim()
            // ASS keeps its start time in the prefix: "Dialogue: 0,0:00:01.00,..."
            is CueMeta.Ass -> m.prefix.split(",").getOrNull(1)?.trim().orEmpty()
        }
        val clock = raw.substringBefore(',').substringBefore('.')
        val parts = clock.split(':')
        if (parts.size < 3) return clock
        val hours = parts[0].trimStart('0')
        val minutes = parts[1].trimStart('0').ifEmpty { "0" }
        return if (hours.isEmpty()) "$minutes:${parts[2]}" else "$hours:${parts[1]}:${parts[2]}"
    }

private val TRAILING_LANG_TAG =
    Regex("""\.(en|eng|english|jp|jpn|ja|hi|hin|es|fr|ar|id|pt|ru|ko|zh)$""", RegexOption.IGNORE_CASE)

/** `Boruto E01.en.srt` + `bn` -> `Boruto E01.bn.srt` */
fun outputName(fileName: String, langTag: String): String {
    val dot = fileName.lastIndexOf('.')
    val ext = if (dot == -1) "srt" else fileName.substring(dot + 1)
    val rawStem = if (dot == -1) fileName else fileName.substring(0, dot)
    // Drop a trailing language tag if the source already carried one.
    val stem = TRAILING_LANG_TAG.replace(rawStem, "")
    return "$stem.$langTag.$ext"
}
