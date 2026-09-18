package com.subtrans.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.subtrans.app.engine.GlossaryEntry
import com.subtrans.app.engine.ReplaceRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Everything that has to survive the app being closed. All of it stays on this
 * device — there is no account, no sync, and no server.
 */

@Serializable
data class AppSettings(
    /** The language the downloaded subtitles are in, when detection is off. */
    val sourceTag: String = "en",
    /**
     * Work out each file's language individually. A mixed archive is the
     * normal case, not the exception, so this defaults on.
     */
    val autoDetectSource: Boolean = true,
    /** The language to translate into. */
    val targetTag: String = "bn",
    /** Only used for the occasional tuning call, never for bulk translation. */
    val geminiKey: String = "",
    val geminiModel: String = "gemini-2.5-flash",
    val tone: String = "স্বাভাবিক কথ্য বাংলা, চরিত্রের বয়স ও সম্পর্ক অনুযায়ী তুমি/তুই/আপনি",
    /** Send the lines the offline engine got wrong to the AI for repair. */
    val aiPolish: Boolean = true,
    /** A ceiling so one bad episode cannot drain the daily AI quota. */
    val maxPolishLines: Int = 60,
    val concurrency: Int = 4,
    /** Characters per display line after translation; 0 disables re-wrapping. */
    val wrapWidth: Int = 42,
    val requireWifiForModels: Boolean = false,
    /** Write the source line under each translation, for watching while learning. */
    val bilingual: Boolean = false,
    /**
     * Save with the source file's exact name instead of adding a language tag.
     * Players auto-load a subtitle only when its name matches the video file,
     * so this is what most people actually want.
     */
    val keepOriginalName: Boolean = false,
    /** Drop empty and repeated cues when saving. */
    val tidyOnSave: Boolean = false,
    /** OpenSubtitles API key, for searching and downloading. */
    val osApiKey: String = "",
    /**
     * Optional OpenSubtitles JWT. A logged-in account has a larger daily
     * quota; the app never asks for a password, so this is pasted in by hand
     * if you want it.
     */
    val osToken: String = "",
)

private val Context.dataStore by preferencesDataStore(name = "subtrans")

private val SETTINGS_KEY = stringPreferencesKey("settings")
private val GLOSSARY_KEY = stringPreferencesKey("glossaries")
private val RULES_KEY = stringPreferencesKey("rules")

class AppStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        prefs[SETTINGS_KEY]
            ?.let { runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull() }
            ?: AppSettings()
    }

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val current = prefs[SETTINGS_KEY]
                ?.let { runCatching { json.decodeFromString<AppSettings>(it) }.getOrNull() }
                ?: AppSettings()
            prefs[SETTINGS_KEY] = json.encodeToString(transform(current))
        }
    }

    val glossaries: Flow<Map<String, List<GlossaryEntry>>> = context.dataStore.data.map { prefs ->
        prefs[GLOSSARY_KEY]
            ?.let {
                runCatching {
                    json.decodeFromString<Map<String, List<GlossaryEntry>>>(it)
                }.getOrNull()
            }
            ?: emptyMap()
    }

    val rules: Flow<List<ReplaceRule>> = context.dataStore.data.map { prefs ->
        prefs[RULES_KEY]
            ?.let { runCatching { json.decodeFromString<List<ReplaceRule>>(it) }.getOrNull() }
            ?: emptyList()
    }

    suspend fun setRules(rules: List<ReplaceRule>) {
        context.dataStore.edit { prefs -> prefs[RULES_KEY] = json.encodeToString(rules) }
    }

    suspend fun setGlossary(series: String, entries: List<GlossaryEntry>) =
        editGlossaries { it + (series to entries) }

    suspend fun removeGlossary(series: String) = editGlossaries { it - series }

    private suspend fun editGlossaries(
        transform: (Map<String, List<GlossaryEntry>>) -> Map<String, List<GlossaryEntry>>,
    ) {
        context.dataStore.edit { prefs ->
            val current = prefs[GLOSSARY_KEY]
                ?.let {
                    runCatching {
                        json.decodeFromString<Map<String, List<GlossaryEntry>>>(it)
                    }.getOrNull()
                }
                ?: emptyMap()
            prefs[GLOSSARY_KEY] = json.encodeToString(transform(current))
        }
    }
}

/**
 * Guesses the series a file belongs to, so its glossary is picked up without
 * the user having to type the name again:
 * `[SubsPlease] Boruto - 042 (1080p).en.srt` -> `Boruto`.
 */
fun guessSeries(fileName: String): String {
    var name = fileName.replace(Regex("""\.[A-Za-z0-9]{1,4}$"""), "")
    name = name.replace(Regex("""^\[[^\]]*\]\s*"""), "")
    name = name.replace(Regex("""[._]+"""), " ")

    val cut = Regex(
        """\s*(-\s*\d{1,4}\b|\bS\d{1,2}\s*E\d{1,3}\b|\bE\d{1,3}\b|\bEp(isode)?\.?\s*\d{1,4}\b|\b\d{3,4}p\b|\(\d{4}\))""",
        RegexOption.IGNORE_CASE,
    ).find(name)?.range?.first ?: -1

    if (cut > 0) name = name.substring(0, cut)
    return name.trim().replace(Regex("""\s{2,}"""), " ").ifEmpty { "Default" }
}
