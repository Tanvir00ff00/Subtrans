package com.subtrans.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * OpenSubtitles REST client.
 *
 * Two things worth knowing before reading further.
 *
 * The daily download limit is enforced on their servers, so being a native app
 * changes nothing about it — a free account gets a handful of downloads a day.
 * [remainingDownloads] is reported after every download and surfaced in the UI
 * before a bulk run starts, because discovering the limit halfway through a
 * season is the worst way to learn it. For whole seasons at once, importing a
 * subtitle pack as a ZIP is the route that has no limit at all.
 *
 * Authentication is by API key only here. A logged-in account has a larger
 * quota, but that needs a username and password, and this app deliberately
 * does not ask for or store one. If you obtain a token yourself you can paste
 * it in and it will be used.
 */
class OpenSubtitles(
    private val apiKey: String,
    /** Optional JWT from an OpenSubtitles login; raises the daily quota. */
    private val token: String = "",
    private val http: OkHttpClient = defaultClient,
) {

    companion object {
        private const val BASE = "https://api.opensubtitles.com/api/v1"

        /**
         * OpenSubtitles asks every client to identify itself. A browser cannot
         * set this header at all, which is one reason the app is native.
         */
        private const val USER_AGENT = "SubTrans v0.1.0"

        private val JSON_MEDIA = "application/json".toMediaType()

        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }
    }

    class ApiException(
        message: String,
        val status: Int = 0,
        val quotaExhausted: Boolean = false,
    ) : Exception(message)

    data class Show(
        val featureId: Int,
        val title: String,
        val year: String?,
        val seasons: Int?,
    )

    data class Entry(
        val fileId: Int,
        val fileName: String,
        val release: String,
        val season: Int?,
        val episode: Int?,
        val episodeTitle: String?,
        val downloads: Int,
        val hearingImpaired: Boolean,
        val trusted: Boolean,
    )

    data class Ticket(
        val link: String,
        val fileName: String,
        /** Downloads left today, as the server reports them. */
        val remaining: Int,
        val resetTime: String?,
    )

    /* ---------------------------------------------------------- transport */

    private fun newRequest(url: String): Request.Builder = Request.Builder()
        .url(url)
        .header("Api-Key", apiKey)
        .header("User-Agent", USER_AGENT)
        .header("Accept", "application/json")
        .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }

    private suspend fun call(request: Request): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw ApiException("OpenSubtitles API key দেওয়া নেই")

        val response = try {
            http.newCall(request).execute()
        } catch (e: Exception) {
            throw ApiException("OpenSubtitles-এ পৌঁছানো গেল না — ইন্টারনেট দেখো")
        }

        response.use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) throw explain(it.code, body)
            body
        }
    }

    private fun explain(code: Int, body: String): ApiException {
        val serverMessage = runCatching {
            lenient.parseToJsonElement(body).jsonObject["message"]?.jsonPrimitive?.contentOrNull
        }.getOrNull()

        val message = when (code) {
            401 -> "টোকেনটা গ্রহণ করা হয়নি — সেটিংসে দেখে নাও।"
            403 -> "API key গ্রহণ করা হয়নি। সেটিংসে key-টা আবার বসাও।"
            406 -> "আজকের ডাউনলোড কোটা শেষ। কাল আবার হবে — অথবা পুরো সিজনের ZIP প্যাক ইমপোর্ট করো, ওতে কোনো সীমা নেই।"
            429 -> "খুব দ্রুত চাওয়া হয়েছে — একটু পরে আবার চেষ্টা করো।"
            else -> serverMessage ?: "HTTP $code"
        }
        return ApiException(message, code, quotaExhausted = code == 406)
    }

    /* ------------------------------------------------------------ search */

    /** Finds TV series matching a name, e.g. "Boruto". */
    suspend fun searchShows(query: String): List<Show> {
        val url = "$BASE/features?query=${encode(query)}&type=tv"
        val data = lenient.parseToJsonElement(call(newRequest(url).build())).jsonObject

        return data["data"]?.jsonArray.orEmpty().mapNotNull { row ->
            val attributes = row.jsonObject["attributes"]?.jsonObject ?: return@mapNotNull null
            val featureId = attributes["feature_id"]?.jsonPrimitive?.intOrNull
                ?: row.jsonObject["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: return@mapNotNull null
            Show(
                featureId = featureId,
                title = attributes["title"]?.jsonPrimitive?.contentOrNull ?: "অজানা",
                year = attributes["year"]?.jsonPrimitive?.contentOrNull,
                seasons = attributes["seasons_count"]?.jsonPrimitive?.intOrNull,
            )
        }
    }

    /**
     * Lists the subtitles for a season. The API pages at 100 at a time, so a
     * long-running series takes a handful of round trips.
     */
    suspend fun listSeason(
        featureId: Int,
        season: Int?,
        language: String,
        onPage: (Int, Int) -> Unit = { _, _ -> },
    ): List<Entry> {
        val found = mutableListOf<Entry>()
        var page = 1
        var totalPages = 1

        while (page <= totalPages && page <= 20) {
            val url = buildString {
                append("$BASE/subtitles?parent_feature_id=$featureId")
                append("&languages=${encode(language)}")
                append("&per_page=100&page=$page")
                append("&order_by=download_count&order_direction=desc")
                if (season != null) append("&season_number=$season")
            }

            val data = lenient.parseToJsonElement(call(newRequest(url).build())).jsonObject
            totalPages = data["total_pages"]?.jsonPrimitive?.intOrNull ?: 1
            onPage(page, totalPages)

            for (row in data["data"]?.jsonArray.orEmpty()) {
                val attributes = row.jsonObject["attributes"]?.jsonObject ?: continue
                val file = attributes["files"]?.jsonArray?.firstOrNull()?.jsonObject ?: continue
                val fileId = file["file_id"]?.jsonPrimitive?.intOrNull ?: continue
                val details = attributes["feature_details"]?.jsonObject

                found += Entry(
                    fileId = fileId,
                    fileName = file["file_name"]?.jsonPrimitive?.contentOrNull
                        ?: "subtitle-$fileId.srt",
                    release = attributes["release"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    season = details?.get("season_number")?.jsonPrimitive?.intOrNull,
                    episode = details?.get("episode_number")?.jsonPrimitive?.intOrNull,
                    episodeTitle = details?.get("title")?.jsonPrimitive?.contentOrNull,
                    downloads = attributes["download_count"]?.jsonPrimitive?.intOrNull ?: 0,
                    hearingImpaired = attributes["hearing_impaired"]?.jsonPrimitive?.contentOrNull == "true",
                    trusted = attributes["from_trusted"]?.jsonPrimitive?.contentOrNull == "true",
                )
            }
            page++
        }
        return found
    }

    /* ---------------------------------------------------------- download */

    /** Spends one download from the daily quota and returns a short-lived link. */
    suspend fun requestDownload(fileId: Int): Ticket {
        val body = """{"file_id":$fileId}""".toRequestBody(JSON_MEDIA)
        val request = newRequest("$BASE/download")
            .header("Content-Type", "application/json")
            .post(body)
            .build()

        val data = lenient.parseToJsonElement(call(request)).jsonObject
        val link = data["link"]?.jsonPrimitive?.contentOrNull
            ?: throw ApiException(
                data["message"]?.jsonPrimitive?.contentOrNull ?: "ডাউনলোড লিংক পাওয়া গেল না"
            )

        return Ticket(
            link = link,
            fileName = data["file_name"]?.jsonPrimitive?.contentOrNull ?: "subtitle-$fileId.srt",
            remaining = data["remaining"]?.jsonPrimitive?.intOrNull ?: 0,
            resetTime = data["reset_time"]?.jsonPrimitive?.contentOrNull,
        )
    }

    /** Fetches the subtitle text itself from a ticket link. */
    suspend fun fetchText(link: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(link).header("User-Agent", USER_AGENT).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ApiException("ফাইল নামানো গেল না (HTTP ${response.code})", response.code)
            }
            response.body?.string().orEmpty()
        }
    }

    /** Downloads left today, when the account can be asked. Null when it cannot. */
    suspend fun remainingDownloads(): Int? = runCatching {
        val data = lenient.parseToJsonElement(call(newRequest("$BASE/infos/user").build()))
        data.jsonObject["data"]?.jsonObject?.get("remaining_downloads")?.jsonPrimitive?.intOrNull
    }.getOrNull()

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
