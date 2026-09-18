package com.subtrans.app.net

/**
 * OpenSubtitles behind the common [SubtitleSource] interface.
 *
 * A thin adapter on purpose: the client underneath is already written and
 * tested, and rewriting it to fit an interface would risk working code for no
 * gain. All this does is translate names and hand the types over.
 *
 * This source is optional. Without a key it reports itself as not ready and
 * the app uses a free source instead — it never blocks a run or nags. The key
 * is free from opensubtitles.com, and what it buys is reach: their catalogue
 * covers anime and small languages that the keyless sources do not.
 */
class OpenSubtitlesSource(
    private val apiKey: String,
    private val token: String = "",
) : SubtitleSource {

    private val client: OpenSubtitles? =
        if (apiKey.isBlank()) null else OpenSubtitles(apiKey, token)

    override val id = "opensubtitles"
    override val label = "OpenSubtitles"
    override val note = "সবচেয়ে বড় ভাণ্ডার, অ্যানিমেও আছে। ফ্রি API key লাগে, দৈনিক সীমা আছে।"
    override val needsKey = true
    override val ready: Boolean get() = client != null
    override val setupHint: String?
        get() = if (ready) null else
            "সেটিংসে একটা ফ্রি OpenSubtitles API key বসালে এই উৎসটা চালু হবে।"

    private fun require(): OpenSubtitles =
        client ?: throw SourceException("OpenSubtitles ব্যবহার করতে একটা API key লাগে")

    override suspend fun searchShows(query: String): List<SourceShow> = wrap {
        require().searchShows(query).map { show ->
            SourceShow(
                id = show.featureId.toString(),
                title = show.title,
                year = show.year,
                // The API reports a count, not which seasons exist, so the
                // numbers are filled in rather than claimed to be known.
                seasons = show.seasons?.takeIf { it > 0 }?.let { (1..it).toList() }.orEmpty(),
            )
        }
    }

    override suspend fun listSeason(
        show: SourceShow,
        season: Int,
        languageTag: String,
        onPage: (Int, Int) -> Unit,
    ): List<SourceEntry> = wrap {
        val featureId = show.id.toIntOrNull()
            ?: throw SourceException("এই সিরিজটি OpenSubtitles-এর নয়")

        require().listSeason(
            featureId = featureId,
            season = season,
            language = languageTag.substringBefore('-'),
            onPage = onPage,
        ).map { entry ->
            SourceEntry(
                id = entry.fileId.toString(),
                fileName = entry.fileName,
                release = entry.release,
                season = entry.season,
                episode = entry.episode,
                episodeTitle = entry.episodeTitle,
                downloads = entry.downloads,
                hearingImpaired = entry.hearingImpaired,
                trusted = entry.trusted,
            )
        }
    }

    override suspend fun download(entry: SourceEntry): SourceFile = wrap {
        val fileId = entry.id.toIntOrNull()
            ?: throw SourceException("এই ফাইলটি OpenSubtitles-এর নয়")

        val ticket = require().requestDownload(fileId)
        SourceFile(
            fileName = ticket.fileName,
            text = require().fetchText(ticket.link),
            remaining = ticket.remaining,
        )
    }

    override suspend fun remainingDownloads(): Int? =
        if (ready) runCatching { require().remainingDownloads() }.getOrNull() else null

    /** Keeps the source's own exception type at the boundary. */
    private inline fun <T> wrap(block: () -> T): T = try {
        block()
    } catch (e: OpenSubtitles.ApiException) {
        throw SourceException(e.message.orEmpty(), e.status, e.quotaExhausted)
    }
}
