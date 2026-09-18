package com.subtrans.app.ai

import com.subtrans.app.engine.GlossaryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * The AI half of the app — and deliberately the small half.
 *
 * It is never asked to translate an episode. It is asked to do the three
 * things the offline engine cannot: work out a series' proper nouns once, fix
 * the handful of lines the engine got wrong, and turn a sentence the user
 * typed into engine rules. That is on the order of twenty calls for a whole
 * series, so a free key lasts indefinitely.
 */
class AiTuner(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val http: OkHttpClient = defaultClient,
) {

    companion object {
        const val DEFAULT_MODEL = "gemini-2.5-flash"
        private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models"
        private val JSON_MEDIA = "application/json".toMediaType()

        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

        private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }
    }

    class AiException(message: String, val status: Int = 0) : Exception(message)

    /* ------------------------------------------------------------ glossary */

    /**
     * Reads a sample of an episode and proposes the names, techniques and
     * places that must be spelled the same way in every episode. Run once per
     * series; the result is what [com.subtrans.app.engine.TermPrep] locks.
     */
    suspend fun buildGlossary(
        sampleLines: List<String>,
        targetLanguage: String,
        seriesName: String?,
    ): List<GlossaryEntry> {
        val sample = sampleLines.take(400).joinToString("\n")
        if (sample.isBlank()) return emptyList()

        val system = buildString {
            appendLine("You build translation glossaries for subtitles going into $targetLanguage.")
            if (!seriesName.isNullOrBlank()) appendLine("Material: $seriesName.")
            appendLine("From the sample, list only recurring proper nouns: character names, place names, named techniques, organisations, and honorific suffixes that matter.")
            appendLine("Skip ordinary vocabulary, skip anything appearing once in passing, and cap the list at 40 entries.")
            appendLine("For each, give the spelling to use in $targetLanguage, chosen so it reads naturally and stays identical across every episode.")
        }

        val schema = buildJsonObject {
            put("type", "ARRAY")
            putJsonObject("items") {
                put("type", "OBJECT")
                putJsonObject("properties") {
                    putJsonObject("source") { put("type", "STRING") }
                    putJsonObject("target") { put("type", "STRING") }
                    putJsonObject("note") { put("type", "STRING") }
                }
                putJsonArray("required") { add("source"); add("target") }
            }
        }

        val text = generate(system, "Subtitle sample:\n\n$sample", schema, temperature = 0.1)
        val rows = parseArray(text) ?: return emptyList()

        return rows.mapNotNull { row ->
            val obj = row as? JsonObject ?: return@mapNotNull null
            val source = obj["source"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val target = obj["target"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (source.isEmpty() || target.isEmpty()) null
            else GlossaryEntry(source, target, obj["note"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty())
        }
    }

    /* -------------------------------------------------------------- polish */

    data class PolishRequest(
        val id: Int,
        val source: String,
        /** What the offline engine produced, for the model to improve on. */
        val draft: String,
        /** A line or two of surrounding dialogue, for context. */
        val context: String = "",
    )

    /**
     * Rewrites only the lines the offline pass flagged. Everything else in the
     * episode is already done and is never sent.
     */
    suspend fun polish(
        lines: List<PolishRequest>,
        targetLanguage: String,
        tone: String,
        glossary: List<GlossaryEntry>,
    ): Map<Int, String> {
        if (lines.isEmpty()) return emptyMap()

        val terms = glossary.take(60).joinToString("\n") { "- ${it.source} => ${it.target}" }
        val system = buildString {
            appendLine("You repair subtitle lines that an offline machine translator handled badly. The target language is $targetLanguage.")
            appendLine()
            appendLine("You receive JSON objects {\"id\", \"s\": source line, \"d\": the machine draft}.")
            appendLine("Return JSON objects {\"id\", \"t\": your corrected line}.")
            appendLine()
            appendLine("Rules:")
            appendLine("1. Return exactly one object per input, with the id copied back verbatim.")
            appendLine("2. Preserve every placeholder of the form @number@ exactly as received. They stand for styling tags and locked names.")
            appendLine("3. Translate only. No notes, no romanisation in brackets, no added quotation marks.")
            appendLine("4. Subtitles are read in about two seconds. Prefer short, idiomatic phrasing over literal word order.")
            appendLine("5. Keep the emotional temperature: shouting stays urgent, muttering stays flat.")
            appendLine()
            appendLine("Register: ${tone.ifBlank { "natural, conversational, the way people actually speak" }}.")
            if (terms.isNotBlank()) {
                appendLine()
                appendLine("These spellings are mandatory:")
                appendLine(terms)
            }
        }

        val payload = buildJsonArray {
            for (line in lines) {
                add(buildJsonObject {
                    put("id", line.id)
                    put("s", line.source)
                    put("d", line.draft)
                    if (line.context.isNotBlank()) put("c", line.context)
                })
            }
        }

        val schema = buildJsonObject {
            put("type", "ARRAY")
            putJsonObject("items") {
                put("type", "OBJECT")
                putJsonObject("properties") {
                    putJsonObject("id") { put("type", "INTEGER") }
                    putJsonObject("t") { put("type", "STRING") }
                }
                putJsonArray("required") { add("id"); add("t") }
            }
        }

        val text = generate(system, "Repair these lines:\n$payload", schema, temperature = 0.3)
        val rows = parseArray(text) ?: return emptyMap()

        val out = mutableMapOf<Int, String>()
        for (row in rows) {
            val obj = row as? JsonObject ?: continue
            val id = runCatching { obj["id"]?.jsonPrimitive?.int }.getOrNull() ?: continue
            val value = obj["t"]?.jsonPrimitive?.contentOrNull?.trim() ?: continue
            if (value.isNotEmpty()) out[id] = value
        }
        return out
    }

    /* ------------------------------------------------------------ transport */

    private suspend fun generate(
        system: String,
        user: String,
        schema: JsonObject?,
        temperature: Double,
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw AiException("Gemini API key দেওয়া নেই")

        val body = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { add(buildJsonObject { put("text", system) }) }
            }
            putJsonArray("contents") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { add(buildJsonObject { put("text", user) }) }
                })
            }
            putJsonObject("generationConfig") {
                put("temperature", temperature)
                if (schema != null) {
                    put("responseMimeType", "application/json")
                    put("responseSchema", schema)
                }
            }
            putJsonArray("safetySettings") {
                for (category in SAFETY_CATEGORIES) {
                    add(buildJsonObject {
                        put("category", category)
                        put("threshold", "BLOCK_ONLY_HIGH")
                    })
                }
            }
        }

        val request = Request.Builder()
            .url("$ENDPOINT/$model:generateContent")
            .addHeader("x-goog-api-key", apiKey)
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()

        http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw AiException(errorMessage(raw, response.code), response.code)
            }
            extractText(raw) ?: throw AiException("মডেল খালি উত্তর দিয়েছে")
        }
    }

    private fun errorMessage(raw: String, code: Int): String {
        val parsed = runCatching {
            lenientJson.parseToJsonElement(raw).jsonObject["error"]
                ?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        if (code == 404) return "\"$model\" নামে কোনো মডেল পাওয়া যায়নি"
        return parsed ?: "HTTP $code"
    }

    private fun extractText(raw: String): String? = runCatching {
        lenientJson.parseToJsonElement(raw).jsonObject["candidates"]
            ?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("content")
            ?.jsonObject?.get("parts")
            ?.jsonArray
            ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            ?.joinToString("")
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun parseArray(text: String): JsonArray? = runCatching {
        val cleaned = text.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```")
            .trim()
        lenientJson.parseToJsonElement(cleaned) as? JsonArray
    }.getOrNull()
}

private val SAFETY_CATEGORIES = listOf(
    "HARM_CATEGORY_HARASSMENT",
    "HARM_CATEGORY_HATE_SPEECH",
    "HARM_CATEGORY_SEXUALLY_EXPLICIT",
    "HARM_CATEGORY_DANGEROUS_CONTENT",
)
