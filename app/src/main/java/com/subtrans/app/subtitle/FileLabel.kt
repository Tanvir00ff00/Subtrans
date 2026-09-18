package com.subtrans.app.subtitle

/**
 * Making a list of three hundred subtitle files readable.
 *
 * Release names put the part that distinguishes one file from another at the
 * very end: every episode of a series begins with the same forty characters
 * and differs only in "E275" near the tail. A list that truncates on the right
 * therefore shows three hundred identical rows — which is exactly what
 * happened.
 *
 * So the episode is pulled out and shown as the heading, and when the whole
 * name is shown it is shortened from the middle, never the end.
 */
object FileLabel {

    // `\b` is useless here: it counts an underscore as part of a word, so
    // `_S1_E275_` — the commonest separator in release names — never matches a
    // pattern anchored with \b. These use explicit alphanumeric lookarounds so
    // an underscore reads as the separator it plainly is.
    private const val BEFORE = """(?<![A-Za-z0-9])"""
    private const val AFTER = """(?![A-Za-z0-9])"""

    private val PATTERNS = listOf(
        // S01E05, S1 E275, S1_E275
        Regex(BEFORE + """S(\d{1,2})[\s._-]*E(\d{1,4})""" + AFTER, RegexOption.IGNORE_CASE),
        // 1x05
        Regex(BEFORE + """(\d{1,2})x(\d{1,4})""" + AFTER, RegexOption.IGNORE_CASE),
    )

    private val EPISODE_ONLY = listOf(
        Regex(BEFORE + """E(?:p(?:isode)?)?[\s._-]*(\d{1,4})""" + AFTER, RegexOption.IGNORE_CASE),
        // "Boruto - 042" — a dash followed by a bare number
        Regex("""\s-\s*(\d{1,4})""" + AFTER),
    )

    data class Episode(val season: Int?, val number: Int)

    /** Pulls the season and episode out of a release name, when they are there. */
    fun episodeOf(fileName: String): Episode? {
        val stem = fileName.substringBeforeLast('.')

        for (pattern in PATTERNS) {
            val match = pattern.find(stem) ?: continue
            val season = match.groupValues[1].toIntOrNull()
            val number = match.groupValues[2].toIntOrNull() ?: continue
            return Episode(season, number)
        }
        for (pattern in EPISODE_ONLY) {
            val match = pattern.find(stem) ?: continue
            val number = match.groupValues[1].toIntOrNull() ?: continue
            // A four-digit number is far more likely to be a year or a
            // resolution than an episode.
            if (number > 2000) continue
            return Episode(null, number)
        }
        return null
    }

    /** A short heading for the row: "S1 · E275", or the bare name if unknown. */
    fun heading(fileName: String): String {
        val episode = episodeOf(fileName) ?: return fileName.substringBeforeLast('.')
        val season = episode.season?.let { "S$it · " } ?: ""
        return season + "E" + episode.number
    }

    /**
     * Shortens from the middle, keeping both ends. The tail is what tells two
     * files apart, so it is never the part that gets cut.
     */
    fun middleEllipsis(text: String, max: Int): String {
        if (max < 8 || text.length <= max) return text
        val keepEnd = (max - 1) * 2 / 3
        val keepStart = max - 1 - keepEnd
        return text.take(keepStart) + "…" + text.takeLast(keepEnd)
    }

    /**
     * Sort key that orders E2 before E10. A plain string sort puts "E10"
     * first, which makes a season list look shuffled.
     */
    fun naturalKey(fileName: String): List<Comparable<*>> {
        val parts = Regex("""\d+|\D+""").findAll(fileName.lowercase()).map { it.value }.toList()
        return parts.map { part ->
            part.toLongOrNull() ?: part
        }
    }

    /** Orders a list of names the way a person would expect to read them. */
    fun <T> sortNaturally(items: List<T>, nameOf: (T) -> String): List<T> =
        items.sortedWith { a, b -> compareKeys(naturalKey(nameOf(a)), naturalKey(nameOf(b))) }

    private fun compareKeys(a: List<Comparable<*>>, b: List<Comparable<*>>): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val x = a[i]
            val y = b[i]
            val result = when {
                x is Long && y is Long -> x.compareTo(y)
                x is Long -> -1
                y is Long -> 1
                else -> (x as String).compareTo(y as String)
            }
            if (result != 0) return result
        }
        return a.size - b.size
    }
}
