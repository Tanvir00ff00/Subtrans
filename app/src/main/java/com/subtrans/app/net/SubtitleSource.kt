package com.subtrans.app.net

/**
 * Where subtitles can be fetched from.
 *
 * The app started with one source that demands an API key, which made the
 * whole download feature unusable until the user went and registered for one.
 * That is a bad first run, so sources are now plural and the ones that need
 * nothing come first.
 *
 * What is actually true about the free ones, measured rather than assumed:
 *
 *  - **Gestdown** answers show search, season listing and file download with
 *    no key, no account and no daily limit. Its catalogue is Addic7ed's, which
 *    is strong on Western television and thin on anime.
 *  - **OpenSubtitles** has by far the widest catalogue, including anime, and
 *    refuses every request without a key — a bare call returns 403 "You cannot
 *    consume this service". No amount of client-side work changes that.
 *
 * So a key buys reach, not function. The app works without one, says so, and
 * never blocks on it.
 */
interface SubtitleSource {

    /** Stable identifier, stored in settings. */
    val id: String

    /** What the user sees in the source picker. */
    val label: String

    /** One line on what this source is good for. */
    val note: String

    /** True when the user must supply credentials before this works. */
    val needsKey: Boolean

    /** True when it can be used right now. */
    val ready: Boolean

    /** What to do about it when [ready] is false. */
    val setupHint: String?

    suspend fun searchShows(query: String): List<SourceShow>

    suspend fun listSeason(
        show: SourceShow,
        season: Int,
        languageTag: String,
        onPage: (page: Int, total: Int) -> Unit = { _, _ -> },
    ): List<SourceEntry>

    /** Fetches one subtitle's text. May spend quota where a source has one. */
    suspend fun download(entry: SourceEntry): SourceFile

    /** Downloads left today, or null when the source has no limit to report. */
    suspend fun remainingDownloads(): Int? = null
}

/** A series, as one source identifies it. */
data class SourceShow(
    /** Opaque to everyone but the source that issued it. */
    val id: String,
    val title: String,
    val year: String? = null,
    /** Seasons the source knows about, when it says. Empty means unknown. */
    val seasons: List<Int> = emptyList(),
)

/** One downloadable subtitle file. */
data class SourceEntry(
    val id: String,
    val fileName: String,
    val release: String = "",
    val season: Int? = null,
    val episode: Int? = null,
    val episodeTitle: String? = null,
    val downloads: Int = 0,
    val hearingImpaired: Boolean = false,
    val trusted: Boolean = false,
)

data class SourceFile(
    val fileName: String,
    val text: String,
    /** Downloads left today, when the source volunteers it. */
    val remaining: Int? = null,
)

class SourceException(
    message: String,
    val status: Int = 0,
    /** The daily allowance is gone; waiting is the only fix. */
    val quotaExhausted: Boolean = false,
) : Exception(message)

/**
 * Collapses many candidates per episode down to the single best one: trusted
 * uploads first, then whatever the most people have downloaded.
 *
 * Every source offers several files per episode and the differences between
 * them are mostly release-group noise, so picking one is better than showing
 * the user a list they have no basis to choose from.
 */
fun bestPerEpisode(entries: List<SourceEntry>): Map<Int, SourceEntry> {
    val best = mutableMapOf<Int, SourceEntry>()
    for (entry in entries) {
        val episode = entry.episode ?: continue
        val current = best[episode]
        if (current == null || score(entry) > score(current)) best[episode] = entry
    }
    return best
}

private fun score(e: SourceEntry): Int =
    (if (e.trusted) 1_000_000 else 0) + e.downloads - (if (e.hearingImpaired) 500 else 0)

/**
 * Gestdown names languages in full ("English"), where the rest of the app uses
 * BCP-47 tags. Only the languages Addic7ed actually carries are worth listing;
 * anything else falls back to English, which is what a translation source is
 * usually wanted for anyway.
 */
internal fun languageName(tag: String): String = when (tag.lowercase().substringBefore('-')) {
    "en" -> "English"
    "bn" -> "Bengali"
    "hi" -> "Hindi"
    "es" -> "Spanish"
    "fr" -> "French"
    "de" -> "German"
    "it" -> "Italian"
    "pt" -> "Portuguese"
    "nl" -> "Dutch"
    "ru" -> "Russian"
    "ar" -> "Arabic"
    "tr" -> "Turkish"
    "pl" -> "Polish"
    "sv" -> "Swedish"
    "id" -> "Indonesian"
    "ko" -> "Korean"
    "ja" -> "Japanese"
    "zh" -> "Chinese"
    else -> "English"
}
