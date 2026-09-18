package com.subtrans.app.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.subtrans.app.ai.AiTuner
import com.subtrans.app.data.AppSettings
import com.subtrans.app.data.AppStore
import com.subtrans.app.data.guessSeries
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.subtrans.app.engine.EpisodeTranslator
import com.subtrans.app.engine.GlossaryEntry
import com.subtrans.app.engine.ReplaceRule
import com.subtrans.app.engine.TranslationEngine
import com.subtrans.app.subtitle.Subtitle
import com.subtrans.app.subtitle.outputName
import com.subtrans.app.subtitle.parse
import com.subtrans.app.subtitle.serialize
import com.subtrans.app.subtitle.serializeBilingual
import com.subtrans.app.subtitle.shifted
import com.subtrans.app.subtitle.tidied
import com.subtrans.app.subtitle.timeLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

enum class JobStatus { Queued, Running, Done, Error, Stopped }

data class JobUi(
    val id: String,
    val fileName: String,
    val status: JobStatus = JobStatus.Queued,
    val done: Int = 0,
    val total: Int = 0,
    /** Lines the offline engine was unsure about. */
    val flagged: Int = 0,
    /** Of those, how many the AI repaired. */
    val polished: Int = 0,
    val error: String? = null,
    /** Bumped on any in-place change, so a viewer knows to re-read the cues. */
    val revision: Int = 0,
) {
    val progress: Float get() = if (total == 0) 0f else done.toFloat() / total
}

/** One line of a subtitle, as the viewer shows it. */
data class CueView(
    val id: Int,
    val number: Int,
    val time: String,
    val source: String,
    val translated: String?,
    val flagged: Boolean,
)

/** What a finished batch cost and produced. */
data class RunStats(
    val files: Int,
    val lines: Int,
    val flagged: Int,
    val polished: Int,
    val seconds: Long,
) {
    /** Share of lines the offline engine handled without help. */
    val offlineShare: Double get() = if (lines == 0) 1.0 else 1.0 - flagged.toDouble() / lines
}

enum class ModelState { Unknown, Missing, Downloading, Ready, Unsupported }

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store = AppStore(app)

    val settings: StateFlow<AppSettings> =
        store.settings.stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    val glossaries: StateFlow<Map<String, List<GlossaryEntry>>> =
        store.glossaries.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _jobs = MutableStateFlow<List<JobUi>>(emptyList())
    val jobs: StateFlow<List<JobUi>> = _jobs.asStateFlow()

    private val _series = MutableStateFlow("")
    val series: StateFlow<String> = _series.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _busyNote = MutableStateFlow<String?>(null)
    val busyNote: StateFlow<String?> = _busyNote.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _modelState = MutableStateFlow(ModelState.Unknown)
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    /** Parsed subtitles, kept out of UI state because they are large and mutable. */
    private val parsed = mutableMapOf<String, Subtitle>()

    /** Which cues the offline pass was unsure about, so the viewer can mark them. */
    private val flaggedIds = mutableMapOf<String, Set<Int>>()

    private var runJob: Job? = null

    private val _lastStats = MutableStateFlow<RunStats?>(null)
    val lastStats: StateFlow<RunStats?> = _lastStats.asStateFlow()

    private val _openJobId = MutableStateFlow<String?>(null)
    val openJobId: StateFlow<String?> = _openJobId.asStateFlow()

    val activeGlossary: List<GlossaryEntry>
        get() = glossaries.value[_series.value].orEmpty()

    fun dismissNotice() { _notice.value = null }

    fun setSeries(name: String) { _series.value = name }

    /* ------------------------------------------------------------- files */

    fun addFiles(uris: List<Uri>) = viewModelScope.launch {
        if (uris.isEmpty()) return@launch
        val resolver = getApplication<Application>().contentResolver
        val added = mutableListOf<JobUi>()

        withContext(Dispatchers.IO) {
            for (uri in uris) {
                val name = displayName(uri) ?: "subtitle.srt"
                val id = uri.toString()
                if (parsed.containsKey(id)) continue
                try {
                    val text = resolver.openInputStream(uri)?.use { stream ->
                        stream.readBytes().toString(Charsets.UTF_8)
                    } ?: continue

                    val sub = parse(name, text)
                    if (sub.cues.isEmpty()) {
                        added += JobUi(id, name, JobStatus.Error, error = "কোনো সংলাপ পাওয়া যায়নি")
                        continue
                    }
                    parsed[id] = sub
                    added += JobUi(id, name, JobStatus.Queued, total = sub.cues.size)
                } catch (e: Exception) {
                    added += JobUi(id, name, JobStatus.Error, error = "ফাইলটা পড়া গেল না")
                }
            }
        }

        val existing = _jobs.value.map { it.id }.toSet()
        _jobs.value = _jobs.value + added.filter { it.id !in existing }

        if (_series.value.isBlank()) {
            added.firstOrNull { it.status != JobStatus.Error }
                ?.let { _series.value = guessSeries(it.fileName) }
        }
    }

    fun clearJobs() {
        if (_running.value) return
        parsed.clear()
        flaggedIds.clear()
        _openJobId.value = null
        _jobs.value = emptyList()
    }

    /* ------------------------------------------------------------ viewer */

    fun openFile(id: String) {
        if (parsed.containsKey(id)) _openJobId.value = id
    }

    fun closeFile() { _openJobId.value = null }

    /**
     * A read-only snapshot of one file's lines. Cues are mutated in place while
     * a translation runs, so the viewer asks for a fresh snapshot rather than
     * holding on to the live objects.
     */
    fun cuesOf(id: String): List<CueView> {
        val sub = parsed[id] ?: return emptyList()
        val flagged = flaggedIds[id].orEmpty()
        return sub.cues.map { cue ->
            CueView(
                id = cue.id,
                number = cue.id + 1,
                time = cue.timeLabel,
                source = cue.text,
                translated = cue.translated,
                flagged = cue.id in flagged,
            )
        }
    }

    private fun displayName(uri: Uri): String? {
        val resolver = getApplication<Application>().contentResolver
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getString(0)
        }
        return DocumentFile.fromSingleUri(getApplication(), uri)?.name
    }

    /* ------------------------------------------------------------ models */

    fun refreshModelState() = viewModelScope.launch {
        val s = settings.value
        if (!TranslationEngine.supports(s.sourceTag) || !TranslationEngine.supports(s.targetTag)) {
            _modelState.value = ModelState.Unsupported
            return@launch
        }
        _modelState.value = try {
            val ready = TranslationEngine.isModelDownloaded(s.sourceTag) &&
                TranslationEngine.isModelDownloaded(s.targetTag)
            if (ready) ModelState.Ready else ModelState.Missing
        } catch (e: Exception) {
            ModelState.Unknown
        }
    }

    fun downloadModels() = viewModelScope.launch {
        val s = settings.value
        val engine = TranslationEngine.create(s.sourceTag, s.targetTag) ?: run {
            _modelState.value = ModelState.Unsupported
            return@launch
        }
        _modelState.value = ModelState.Downloading
        try {
            engine.use { it.ensureModel(s.requireWifiForModels) }
            _modelState.value = ModelState.Ready
            _notice.value = "ভাষার মডেল নামানো হয়েছে — এখন থেকে অনুবাদ অফলাইনেই চলবে।"
        } catch (e: Exception) {
            _modelState.value = ModelState.Missing
            _notice.value = "মডেল নামানো গেল না: ${e.message ?: "অজানা সমস্যা"}"
        }
    }

    /* --------------------------------------------------------- translate */

    fun start() {
        if (_running.value) return
        val queue = _jobs.value.filter {
            it.status == JobStatus.Queued || it.status == JobStatus.Stopped
        }
        if (queue.isEmpty()) return

        runJob = viewModelScope.launch {
            _running.value = true
            _notice.value = null
            val s = settings.value

            val engine = TranslationEngine.create(s.sourceTag, s.targetTag)
            if (engine == null) {
                _notice.value = "এই ভাষা জোড়াটা ML Kit সাপোর্ট করে না।"
                _modelState.value = ModelState.Unsupported
                _running.value = false
                return@launch
            }

            try {
                _busyNote.value = "ভাষার মডেল প্রস্তুত করছি…"
                engine.ensureModel(s.requireWifiForModels)
                _modelState.value = ModelState.Ready
                _busyNote.value = null

                val startedAt = System.currentTimeMillis()
                var statLines = 0
                var statFlagged = 0
                var statPolished = 0
                var statFiles = 0

                val glossary = activeGlossary
                val translator = EpisodeTranslator(engine, glossary, rules.value, s.concurrency)
                val tuner = if (s.aiPolish && s.geminiKey.isNotBlank()) {
                    AiTuner(s.geminiKey, s.geminiModel)
                } else null

                for (item in queue) {
                    if (!_running.value) break
                    val sub = parsed[item.id] ?: continue
                    patch(item.id) { it.copy(status = JobStatus.Running, done = 0, polished = 0) }

                    val report = translator.translate(sub) { done, total ->
                        patch(item.id) { it.copy(done = done, total = total) }
                    }
                    flaggedIds[item.id] = report.flagged.mapTo(mutableSetOf()) { it.cueId }
                    patch(item.id) { it.copy(flagged = report.flagged.size) }

                    var polished = 0
                    if (tuner != null && report.flagged.isNotEmpty()) {
                        patch(item.id) { it.copy(status = JobStatus.Running) }
                        polished = polish(translator, tuner, sub, report, s)
                    }

                    patch(item.id) {
                        it.copy(status = JobStatus.Done, done = it.total, polished = polished)
                    }

                    statFiles++
                    statLines += report.total
                    statFlagged += report.flagged.size
                    statPolished += polished
                }

                if (statFiles > 0) {
                    _lastStats.value = RunStats(
                        files = statFiles,
                        lines = statLines,
                        flagged = statFlagged,
                        polished = statPolished,
                        seconds = (System.currentTimeMillis() - startedAt) / 1000,
                    )
                }
            } catch (e: Exception) {
                _notice.value = e.message ?: "অনুবাদ চলাকালীন সমস্যা হয়েছে"
            } finally {
                engine.close()
                _busyNote.value = null
                _running.value = false
            }
        }
    }

    /** Sends only the flagged lines to the AI, capped so a quota cannot vanish. */
    private suspend fun polish(
        translator: EpisodeTranslator,
        tuner: AiTuner,
        sub: Subtitle,
        report: com.subtrans.app.engine.EpisodeReport,
        s: AppSettings,
    ): Int = try {
        val take = report.flagged.take(s.maxPolishLines)
        val requests = take.map {
            AiTuner.PolishRequest(id = it.cueId, source = it.preparedSource, draft = it.draft)
        }
        val corrections = tuner.polish(requests, languageName(s.targetTag), s.tone, activeGlossary)
        translator.applyPolish(sub, take, corrections)
    } catch (e: Exception) {
        _notice.value = "AI পলিশ করা গেল না: ${e.message ?: "অজানা সমস্যা"} — অফলাইন অনুবাদটা ঠিকই আছে।"
        0
    }

    fun stop() {
        _running.value = false
        runJob?.cancel()
        _jobs.value = _jobs.value.map {
            if (it.status == JobStatus.Running) it.copy(status = JobStatus.Stopped) else it
        }
    }

    /* ---------------------------------------------------------- glossary */

    fun buildGlossary() = viewModelScope.launch {
        val s = settings.value
        if (s.geminiKey.isBlank()) {
            _notice.value = "গ্লসারি বানাতে সেটিংসে Gemini API key লাগবে।"
            return@launch
        }
        val source = _jobs.value.firstNotNullOfOrNull { parsed[it.id] } ?: run {
            _notice.value = "আগে একটা সাবটাইটেল ফাইল দাও।"
            return@launch
        }

        _busyNote.value = "এপিসোড পড়ে নাম খুঁজছি…"
        try {
            val name = _series.value.ifBlank { guessSeries(source.fileName) }
            if (_series.value.isBlank()) _series.value = name

            val found = AiTuner(s.geminiKey, s.geminiModel).buildGlossary(
                sampleLines = source.cues.take(400).map { it.text },
                targetLanguage = languageName(s.targetTag),
                seriesName = name.takeIf { it != "Default" },
            )
            if (found.isEmpty()) {
                _notice.value = "গ্লসারিতে রাখার মতো কিছু পাওয়া গেল না।"
            } else {
                // Anything edited by hand wins over a fresh suggestion.
                val merged = LinkedHashMap<String, GlossaryEntry>()
                for (entry in glossaries.value[name].orEmpty()) merged[entry.source.lowercase()] = entry
                for (entry in found) merged.putIfAbsent(entry.source.lowercase(), entry)
                store.setGlossary(name, merged.values.toList())
                _notice.value = "${found.size}টি শব্দ যোগ হলো — গ্লসারি ট্যাবে দেখে নাও।"
            }
        } catch (e: Exception) {
            _notice.value = e.message ?: "গ্লসারি বানানো গেল না"
        } finally {
            _busyNote.value = null
        }
    }

    fun setGlossary(series: String, entries: List<GlossaryEntry>) = viewModelScope.launch {
        store.setGlossary(series, entries)
    }

    fun removeGlossary(series: String) = viewModelScope.launch { store.removeGlossary(series) }

    /* ------------------------------------------------------------ export */

    fun exportAll(treeUri: Uri) = viewModelScope.launch {
        val done = _jobs.value.filter { it.status == JobStatus.Done }
        if (done.isEmpty()) {
            _notice.value = "এখনো কোনো ফাইল অনুবাদ হয়নি।"
            return@launch
        }

        _busyNote.value = "ফাইল সেভ করছি…"
        val context = getApplication<Application>()
        val tag = settings.value.targetTag
        var written = 0

        withContext(Dispatchers.IO) {
            val dir = DocumentFile.fromTreeUri(context, treeUri)
            if (dir == null || !dir.canWrite()) {
                _notice.value = "ওই ফোল্ডারে লেখার অনুমতি পাওয়া গেল না।"
                return@withContext
            }
            for (item in done) {
                val text = renderFile(item.id) ?: continue
                val name = outputName(item.fileName, tag)
                dir.findFile(name)?.delete()
                val file = dir.createFile("application/x-subrip", name) ?: continue
                context.contentResolver.openOutputStream(file.uri)?.use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                }
                written++
            }
        }

        _busyNote.value = null
        _notice.value = if (written > 0) "$written টি ফাইল সেভ হয়েছে।" else "কিছু সেভ করা গেল না।"
    }

    /* ------------------------------------------------------ file actions */

    fun removeJob(id: String) {
        if (_running.value) return
        parsed.remove(id)
        flaggedIds.remove(id)
        if (_openJobId.value == id) _openJobId.value = null
        _jobs.value = _jobs.value.filterNot { it.id == id }
    }

    /**
     * Nudges every timestamp in one file. Subtitles cut for a different release
     * of the same episode are often a second or two out; this rescues them
     * instead of throwing them away.
     */
    fun shiftTiming(id: String, offsetMs: Long) {
        val sub = parsed[id] ?: return
        parsed[id] = shifted(sub, offsetMs)
        touch(id)
        val seconds = offsetMs / 1000.0
        _notice.value = "টাইমিং %+.1f সেকেন্ড সরানো হয়েছে।".format(seconds)
    }

    /** Drops empty cues and cues that repeat the line before them. */
    fun tidyFile(id: String) {
        val sub = parsed[id] ?: return
        val (cleaned, removed) = tidied(sub)
        if (removed == 0) {
            _notice.value = "বাদ দেওয়ার মতো কিছু পাওয়া যায়নি।"
            return
        }
        parsed[id] = cleaned
        flaggedIds.remove(id)
        patch(id) { it.copy(total = cleaned.cues.size, done = cleaned.cues.size, flagged = 0) }
        _notice.value = "$removed টি খালি বা পুনরাবৃত্ত লাইন বাদ পড়েছে।"
    }

    /** Hand-edits one translated line. */
    fun editCue(jobId: String, cueId: Int, text: String) {
        val cue = parsed[jobId]?.cues?.firstOrNull { it.id == cueId } ?: return
        cue.translated = text.ifBlank { null }
        // An edited line is no longer one the engine is unsure about.
        flaggedIds[jobId] = flaggedIds[jobId].orEmpty() - cueId
        patch(jobId) { it.copy(flagged = flaggedIds[jobId]?.size ?: 0) }
        touch(jobId)
    }

    /** Throws away a hand edit and leaves the line in its source language. */
    fun resetCue(jobId: String, cueId: Int) {
        val cue = parsed[jobId]?.cues?.firstOrNull { it.id == cueId } ?: return
        cue.translated = null
        touch(jobId)
    }

    /** The file exactly as it would be written, honouring the export settings. */
    private fun renderFile(id: String): String? {
        var sub = parsed[id] ?: return null
        val s = settings.value
        if (s.tidyOnSave) sub = tidied(sub).first
        return if (s.bilingual) serializeBilingual(sub) else serialize(sub)
    }

    fun exportOne(id: String, target: Uri) = viewModelScope.launch {
        val text = renderFile(id) ?: return@launch
        withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openOutputStream(target)?.use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            }
        }
        _notice.value = "ফাইলটা সেভ হয়েছে।"
    }

    fun suggestedFileName(id: String): String {
        val job = _jobs.value.firstOrNull { it.id == id } ?: return "subtitle.srt"
        return outputName(job.fileName, settings.value.targetTag)
    }

    /**
     * Asks ML Kit which language a file is actually in. Subtitle packs are
     * mislabelled often enough that translating from the wrong source is a
     * real way to waste an afternoon.
     */
    fun detectSourceLanguage(id: String) = viewModelScope.launch {
        val sub = parsed[id] ?: return@launch
        val sample = sub.cues.take(80).joinToString(" ") { it.text }.take(2000)
        if (sample.isBlank()) return@launch

        _busyNote.value = "ভাষা শনাক্ত করছি…"
        try {
            val tag = LanguageIdentification.getClient().use { client ->
                client.identifyLanguage(sample).await()
            }
            _busyNote.value = null
            when {
                tag == "und" -> _notice.value = "ভাষা শনাক্ত করা গেল না।"
                tag == settings.value.sourceTag ->
                    _notice.value = "ফাইলটা ${languageName(tag)}-তেই আছে, সেটিংস ঠিক আছে।"
                else -> {
                    updateSettings { it.copy(sourceTag = tag) }
                    _notice.value = "ফাইলটা ${languageName(tag)}-তে — উৎস ভাষা বদলে দিলাম।"
                }
            }
        } catch (e: Exception) {
            _busyNote.value = null
            _notice.value = "ভাষা শনাক্ত করা গেল না: ${e.message ?: "অজানা সমস্যা"}"
        }
    }

    /* ------------------------------------------------------------- rules */

    val rules: StateFlow<List<ReplaceRule>> =
        store.rules.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun setRules(next: List<ReplaceRule>) = viewModelScope.launch { store.setRules(next) }

    /* ------------------------------------------------------------ models */

    private val _downloadedModels = MutableStateFlow<List<String>>(emptyList())
    val downloadedModels: StateFlow<List<String>> = _downloadedModels.asStateFlow()

    fun refreshDownloadedModels() = viewModelScope.launch {
        _downloadedModels.value = runCatching { TranslationEngine.downloadedTags() }
            .getOrDefault(emptyList())
            .sorted()
    }

    /** Frees the storage a language model takes, when it is no longer needed. */
    fun deleteModel(tag: String) = viewModelScope.launch {
        try {
            TranslationEngine.deleteModel(tag)
            _notice.value = "${languageName(tag)} মডেল মুছে ফেলা হয়েছে।"
        } catch (e: Exception) {
            _notice.value = "মডেল মোছা গেল না: ${e.message ?: "অজানা সমস্যা"}"
        }
        refreshDownloadedModels()
        refreshModelState()
    }

    /** Bumps the job so a viewer watching it rebuilds its snapshot. */
    private fun touch(id: String) = patch(id) { it.copy(revision = it.revision + 1) }

    /* ---------------------------------------------------------- settings */

    fun updateSettings(transform: (AppSettings) -> AppSettings) = viewModelScope.launch {
        store.updateSettings(transform)
        refreshModelState()
    }

    private fun patch(id: String, transform: (JobUi) -> JobUi) {
        _jobs.value = _jobs.value.map { if (it.id == id) transform(it) else it }
    }
}

/** ML Kit works in tags; the AI prompts read better with a real language name. */
fun languageName(tag: String): String = LANGUAGE_NAMES[tag] ?: tag

val LANGUAGE_NAMES: Map<String, String> = mapOf(
    "bn" to "Bengali (বাংলা)",
    "hi" to "Hindi (हिन्दी)",
    "en" to "English",
    "ur" to "Urdu (اردو)",
    "ar" to "Arabic (العربية)",
    "id" to "Indonesian",
    "es" to "Spanish (Español)",
    "tr" to "Turkish (Türkçe)",
    "ja" to "Japanese (日本語)",
    "ko" to "Korean (한국어)",
    "zh" to "Chinese (中文)",
    "ta" to "Tamil (தமிழ்)",
    "te" to "Telugu (తెలుగు)",
    "mr" to "Marathi (मराठी)",
    "gu" to "Gujarati (ગુજરાતી)",
    "fr" to "French (Français)",
    "de" to "German (Deutsch)",
    "ru" to "Russian (Русский)",
    "pt" to "Portuguese (Português)",
    "th" to "Thai (ไทย)",
    "vi" to "Vietnamese (Tiếng Việt)",
)
