package com.subtrans.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Gestdown: subtitles with nothing to sign up for.
 *
 * A public proxy in front of Addic7ed, the same one Bazarr uses. Every step of
 * the path — searching a series, listing a season, fetching the file — was
 * checked against the live service with no key, no account and no headers
 * beyond a user agent, and all three answered.
 *
 * Its limit is catalogue, not access: Addic7ed indexes Western television
 * thoroughly and anime barely. Searching it for "Boruto" returns nothing at
 * all, and "Naruto Shippuden" returns three of its seasons. That is a fact
 * about the library, not something the client can work around, so the app
 * says so rather than showing an empty list and letting the user guess.
 *
 * There is no daily allowance to run out of, which makes this the better
 * source for a whole season whenever the series is actually in it.
 */
class Gestdown(
    private val http: OkHttpClient = defaultClient,
    private val base: String = BASE,
) : SubtitleSource {

    companion object {
        const val BASE = "https://api.gestdown.info"
        private const val USER_AGENT = "SubTrans v0.1.0"

        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }
    }

    override val id = "gestdown"
    override val label = "Gestdown (Addic7ed)"
    override val note = "কোনো key লাগে না, দৈনিক সীমা নেই। পশ্চিমা সিরিজে ভালো, অ্যানিমে কম।"
    override val needsKey = false
    override val ready = true
    override val setupHint: String? = null

    /* ---------------------------------------------------------- transport */

    private suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            when {
                response.isSuccessful -> body
                // A series the catalogue has never heard of. Not an error the
                // user can act on, so it is reported as "nothing found".
                response.code == 404 -> ""
                else -> throw SourceException(
                    "Gestdown সাড়া দিল না (HTTP ${response.code})",
                    response.code,
                )
            }
        }
    }

    /* ------------------------------------------------------------ search */

    override suspend fun searchShows(query: String): List<SourceShow> =
        parseShows(get("$base/shows/search/${encode(query.trim())}"))

    override suspend fun listSeason(
        show: SourceShow,
        season: Int,
        languageTag: String,
        onPage: (Int, Int) -> Unit,
    ): List<SourceEntry> {
        onPage(1, 1)
        val language = languageName(languageTag)
        return parseSeason(get("$base/shows/${show.id}/$season/${encode(language)}"))
    }

    /* ---------------------------------------------------------- download */

    override suspend fun download(entry: SourceEntry): SourceFile {
        val text = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$base/subtitles/download/${entry.id}")
                .header("User-Agent", USER_AGENT)
                .build()

            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw SourceException(
                        "ফাইল নামানো গেল না (HTTP ${response.code})",
                        response.code,
                    )
                }
                response.body?.string().orEmpty()
            }
        }
        if (text.isBlank()) throw SourceException("ফাইলটি খালি এসেছে")
        return SourceFile(fileName = entry.fileName, text = text)
    }

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

/* -------------------------------------------------------------- parsing *
 * Kept as free functions so the shapes can be tested against real captured
 * responses without a network or a device.
 * ---------------------------------------------------------------------- */

private val parser = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun parseShows(body: String): List<SourceShow> {
    if (body.isBlank()) return emptyList()
    val root = runCatching { parser.parseToJsonElement(body).jsonObject }.getOrNull()
        ?: return emptyList()

    return root["shows"]?.jsonArray.orEmpty().mapNotNull { row ->
        val show = row.jsonObject
        val id = show["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        SourceShow(
            id = id,
            title = show["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
            seasons = show["seasons"]?.jsonArray
                .orEmpty()
                .mapNotNull { it.jsonPrimitive.intOrNull }
                .sorted(),
        )
    }
}

internal fun parseSeason(body: String): List<SourceEntry> {
    if (body.isBlank()) return emptyList()
    val root = runCatching { parser.parseToJsonElement(body).jsonObject }.getOrNull()
        ?: return emptyList()

    val found = mutableListOf<SourceEntry>()

    for (row in root["episodes"]?.jsonArray.orEmpty()) {
        val episode = row.jsonObject
        val season = episode["season"]?.jsonPrimitive?.intOrNull
        val number = episode["number"]?.jsonPrimitive?.intOrNull
        val title = episode["title"]?.jsonPrimitive?.contentOrNull
        val showName = episode["show"]?.jsonPrimitive?.contentOrNull.orEmpty()

        for (item in episode["subtitles"]?.jsonArray.orEmpty()) {
            val subtitle = item.jsonObject
            val subtitleId = subtitle["subtitleId"]?.jsonPrimitive?.contentOrNull ?: continue

            found += SourceEntry(
                id = subtitleId,
                fileName = buildFileName(showName, season, number, title),
                release = subtitle["version"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                season = season,
                episode = number,
                episodeTitle = title,
                downloads = subtitle["downloadCount"]?.jsonPrimitive?.intOrNull ?: 0,
                hearingImpaired = subtitle["hearingImpaired"]?.jsonPrimitive?.booleanOrNull ?: false,
                // Addic7ed marks a subtitle "completed" once it is fully
                // translated and proofed, which is the closest thing it has to
                // the trust flag the UI already sorts on.
                trusted = subtitle["completed"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
    }
    return found
}

/**
 * Builds the name the file will be saved under. The app's own episode parser
 * reads `S01E05` out of this later, so the shape matters more than it looks.
 */
internal fun buildFileName(show: String, season: Int?, episode: Int?, title: String?): String {
    val stem = buildString {
        append(if (show.isBlank()) "Subtitle" else show)
        if (season != null && episode != null) {
            append(" - S%02dE%02d".format(season, episode))
        }
        if (!title.isNullOrBlank()) append(" - $title")
    }
    return sanitise(stem) + ".srt"
}

/** Strips what a file name cannot carry, on any of the platforms involved. */
private fun sanitise(name: String): String =
    name.replace(Regex("""[\\/:*?"<>|]"""), "").replace(Regex("""\s+"""), " ").trim()
