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
import com.subtrans.app.engine.LanguageGuard
import com.subtrans.app.engine.QualityCheck
import com.subtrans.app.engine.ReplaceRule
import com.subtrans.app.engine.SourcePlan
import com.subtrans.app.engine.TranslationEngine
import com.subtrans.app.net.FolderScan
import com.subtrans.app.net.OpenSubtitles
import com.subtrans.app.net.ZipExport
import com.subtrans.app.net.ZipImport
import com.subtrans.app.net.bestPerEpisode
import com.subtrans.app.subtitle.Subtitle
import com.subtrans.app.subtitle.SubtitleText
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

enum class JobStatus {
    Queued, Running, Done, Error, Stopped,

    /** Already in the target language: kept as-is and still exported. */
    Passthrough,
}

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
    /** Something worth reading, but the file still translated. */
    val warning: String? = null,
    /** Bumped on any in-place change, so a viewer knows to re-read the cues. */
    val revision: Int = 0,
    /** Folder this file came from, relative to what the user picked. */
    val relativeDir: String = "",
    /** The language this file was read as, once detected. */
    val sourceTag: String = "",
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
    /** Files already in the target language, copied through untouched. */
    val passedThrough: Int = 0,
    /** Which source languages the batch turned out to contain. */
    val languages: List<String> = emptyList(),
) {
    /** Share of lines the offline engine handled without help. */
    val offlineShare: Double get() = if (lines == 0) 1.0 else 1.0 - flagged.toDouble() / lines
}

/** One episode as offered by a subtitle source, already narrowed to the best upload. */
data class EpisodeOption(val episode: Int, val entry: OpenSubtitles.Entry)

/** Everything the search tab is currently showing. */
data class SearchState(
    val query: String = "",
    /** A short message while a request is in flight, or null when idle. */
    val busy: String? = null,
    val shows: List<OpenSubtitles.Show> = emptyList(),
    val show: OpenSubtitles.Show? = null,
    val season: Int = 1,
    val episodes: List<EpisodeOption> = emptyList(),
    val selected: Set<Int> = emptySet(),
    /** Downloads left today, when the server tells us. */
    val remaining: Int? = null,
    val error: String? = null,
)

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

    /**
     * Fingerprints of what is already queued. Subtitle packs routinely carry
     * the same file under two names, and translating it twice wastes a run.
     */
    private val contentFingerprints = mutableMapOf<String, String>()

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
                    // Not assumed to be UTF-8: a UTF-16 or Windows-1252 file
                    // decoded as UTF-8 still parses, and is ruined by then.
                    val text = resolver.openInputStream(uri)?.use { stream ->
                        SubtitleText.decode(stream.readBytes()).text
                    } ?: continue

                    val sub = parse(name, text)
                    if (sub.cues.isEmpty()) {
                        added += JobUi(id, name, JobStatus.Error, error = "কোনো সংলাপ পাওয়া যায়নি")
                        continue
                    }
                    val fingerprint = fingerprintOf(sub)
                    if (contentFingerprints.containsKey(fingerprint)) {
                        added += JobUi(
                            id, name, JobStatus.Error,
                            error = "এই লেখাটা তালিকায় আগেই আছে, অন্য নামে — দুবার অনুবাদ করার দরকার নেই।",
                        )
                        continue
                    }
                    contentFingerprints[fingerprint] = id
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
        contentFingerprints.clear()
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

    /**
     * Identifies a subtitle by its content rather than its name, so the same
     * episode arriving twice under different names is caught.
     */
    private fun fingerprintOf(sub: Subtitle): String =
        "${sub.cues.size}:${sub.cues.joinToString("") { it.text }.hashCode()}"

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
            val startedAt = System.currentTimeMillis()

            try {
                // Phase one: work out what each file actually is. A dropped
                // archive is rarely uniform — some English, some Hindi, and a
                // few already translated from an earlier run.
                val byLanguage = linkedMapOf<String, MutableList<JobUi>>()
                var passedThrough = 0
                var refused = 0

                for ((index, item) in queue.withIndex()) {
                    if (!_running.value) break
                    val sub = parsed[item.id] ?: continue
                    _busyNote.value = "ভাষা দেখছি ${index + 1}/${queue.size}…"

                    val lines = sub.cues.map { it.text }
                    val script = LanguageGuard.dominantScriptOfFile(lines)
                    val detected = if (s.autoDetectSource) detectTag(lines) else null

                    val decision = SourcePlan.decide(
                        detected = detected,
                        script = script,
                        configured = s.sourceTag,
                        target = s.targetTag,
                        supports = TranslationEngine::supports,
                    )

                    when (decision) {
                        // Already done. It still belongs in the output: leaving
                        // it out would hand back ninety-five files for a hundred
                        // given, and the missing ones would be the finished ones.
                        is SourcePlan.Decision.PassThrough -> {
                            passedThrough++
                            patch(item.id) {
                                it.copy(
                                    status = JobStatus.Passthrough,
                                    done = it.total,
                                    sourceTag = s.targetTag,
                                    warning = "আগে থেকেই ${languageName(s.targetTag)}-তে — " +
                                        "অপরিবর্তিত রেখে আউটপুটে রাখা হয়েছে।",
                                )
                            }
                        }

                        is SourcePlan.Decision.Unsupported -> {
                            refused++
                            patch(item.id) {
                                it.copy(
                                    status = JobStatus.Error,
                                    error = decision.sourceTag
                                        ?.let { t -> "${languageName(t)} থেকে অনুবাদের মডেল নেই।" }
                                        ?: "ফাইলটা কোন ভাষায় বোঝা গেল না।",
                                )
                            }
                        }

                        is SourcePlan.Decision.Translate -> {
                            patch(item.id) {
                                it.copy(
                                    sourceTag = decision.sourceTag,
                                    warning = if (decision.confident) null else
                                        "ভাষা নিশ্চিত হওয়া গেল না — সেটিংসের " +
                                            "${languageName(decision.sourceTag)} ধরে নেওয়া হলো।",
                                )
                            }
                            byLanguage.getOrPut(decision.sourceTag) { mutableListOf() } += item
                        }
                    }
                }
                _busyNote.value = null

                // Phase two: one model per language rather than per file.
                var statLines = 0
                var statFlagged = 0
                var statPolished = 0
                var statFiles = 0

                val glossary = activeGlossary
                val tuner = if (s.aiPolish && s.geminiKey.isNotBlank()) {
                    AiTuner(s.geminiKey, s.geminiModel)
                } else null

                for ((sourceTag, group) in byLanguage) {
                    if (!_running.value) break
                    val engine = TranslationEngine.create(sourceTag, s.targetTag) ?: continue

                    try {
                        _busyNote.value =
                            "${languageName(sourceTag)} → ${languageName(s.targetTag)} মডেল প্রস্তুত করছি…"
                        engine.ensureModel(s.requireWifiForModels)
                        _modelState.value = ModelState.Ready
                        _busyNote.value = null

                        val translator = EpisodeTranslator(
                            engine, glossary, rules.value, s.concurrency, wrapWidth = s.wrapWidth,
                        )

                        for (item in group) {
                            if (!_running.value) break
                            val sub = parsed[item.id] ?: continue
                            patch(item.id) {
                                it.copy(status = JobStatus.Running, done = 0, polished = 0)
                            }

                            val report = translator.translate(sub) { done, total ->
                                patch(item.id) { it.copy(done = done, total = total) }
                            }
                            flaggedIds[item.id] = report.flagged.mapTo(mutableSetOf()) { it.cueId }
                            patch(item.id) { it.copy(flagged = report.flagged.size) }

                            var polished = 0
                            if (tuner != null && report.flagged.isNotEmpty()) {
                                polished = polish(translator, tuner, sub, report, s)
                            }

                            val untouched = report.flagCounts[QualityCheck.Flag.UNCHANGED] ?: 0
                            val untouchedShare =
                                if (report.total == 0) 0.0 else untouched.toDouble() / report.total

                            patch(item.id) {
                                it.copy(
                                    status = JobStatus.Done,
                                    done = it.total,
                                    polished = polished,
                                    warning = if (untouchedShare > 0.4) {
                                        "বেশিরভাগ লাইন অপরিবর্তিত ফিরেছে — ভাষার জোড়া দেখে নাও।"
                                    } else null,
                                )
                            }

                            statFiles++
                            statLines += report.total
                            statFlagged += report.flagged.size
                            statPolished += polished
                        }
                    } finally {
                        engine.close()
                    }
                }

                if (statFiles > 0 || passedThrough > 0) {
                    _lastStats.value = RunStats(
                        files = statFiles,
                        lines = statLines,
                        flagged = statFlagged,
                        polished = statPolished,
                        seconds = (System.currentTimeMillis() - startedAt) / 1000,
                        passedThrough = passedThrough,
                        languages = byLanguage.keys.toList(),
                    )
                }

                // Every file is accounted for out loud, so a missing one is
                // never discovered later by counting the output.
                _notice.value = buildString {
                    append("$statFiles টি অনুবাদ হয়েছে")
                    if (passedThrough > 0) append(", $passedThrough টি আগে থেকেই ${languageName(s.targetTag)}-তে ছিল")
                    if (refused > 0) append(", $refused টি পারা যায়নি")
                    append("। মোট ${queue.size} টির মধ্যে ${statFiles + passedThrough} টি সেভ করার জন্য প্রস্তুত।")
                }
            } catch (e: Exception) {
                _notice.value = e.message ?: "অনুবাদ চলাকালীন সমস্যা হয়েছে"
            } finally {
                _busyNote.value = null
                _running.value = false
            }
        }
    }

    /**
     * Asks ML Kit what language a file is in. Returns null when it has no
     * confident opinion, which the caller treats as "fall back to the script
     * and then to the configured source" rather than as a failure.
     */
    private suspend fun detectTag(lines: List<String>): String? {
        val sample = lines.take(120).joinToString(" ").take(3000)
        if (sample.isBlank()) return null
        return runCatching {
            LanguageIdentification.getClient().use { it.identifyLanguage(sample).await() }
        }.getOrNull()?.takeIf { it != "und" }
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
        val done = _jobs.value.filter { it.status in EXPORTABLE }
        if (done.isEmpty()) {
            _notice.value = "এখনো কোনো ফাইল অনুবাদ হয়নি।"
            return@launch
        }

        _busyNote.value = "ফাইল সেভ করছি…"
        val context = getApplication<Application>()
        var written = 0

        withContext(Dispatchers.IO) {
            val dir = DocumentFile.fromTreeUri(context, treeUri)
            if (dir == null || !dir.canWrite()) {
                _notice.value = "ওই ফোল্ডারে লেখার অনুমতি পাওয়া গেল না।"
                return@withContext
            }
            for (item in done) {
                val text = renderFile(item.id) ?: continue
                val name = outputNameFor(item.fileName)
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

    /* ------------------------------------------------- subtitle sources */

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()

    private fun openSubtitles(): OpenSubtitles? {
        val s = settings.value
        if (s.osApiKey.isBlank()) {
            _search.value = _search.value.copy(
                error = "সেটিংসে OpenSubtitles API key বসাও — ফ্রি অ্যাকাউন্টে পাওয়া যায়।",
            )
            return null
        }
        return OpenSubtitles(s.osApiKey, s.osToken)
    }

    fun setSearchQuery(q: String) { _search.value = _search.value.copy(query = q, error = null) }

    fun searchShows() = viewModelScope.launch {
        val client = openSubtitles() ?: return@launch
        val query = _search.value.query.trim()
        if (query.isBlank()) return@launch

        _search.value = _search.value.copy(busy = "সিরিজ খুঁজছি…", error = null, shows = emptyList())
        try {
            val shows = client.searchShows(query)
            _search.value = _search.value.copy(
                busy = null,
                shows = shows,
                show = null,
                episodes = emptyList(),
                selected = emptySet(),
                error = if (shows.isEmpty()) "\"$query\" নামে কিছু পাওয়া গেল না।" else null,
            )
        } catch (e: Exception) {
            _search.value = _search.value.copy(busy = null, error = e.message)
        }
    }

    fun pickShow(show: OpenSubtitles.Show) {
        _search.value = _search.value.copy(show = show, episodes = emptyList(), selected = emptySet())
        if (_series.value.isBlank()) _series.value = show.title
        loadEpisodes()
    }

    fun setSeason(season: Int) {
        _search.value = _search.value.copy(season = season)
        loadEpisodes()
    }

    fun backToShows() {
        _search.value = _search.value.copy(show = null, episodes = emptyList(), selected = emptySet())
    }

    fun loadEpisodes() = viewModelScope.launch {
        val client = openSubtitles() ?: return@launch
        val state = _search.value
        val show = state.show ?: return@launch

        _search.value = state.copy(busy = "এপিসোড তালিকা আনছি…", error = null)
        try {
            val entries = client.listSeason(
                featureId = show.featureId,
                season = state.season,
                language = settings.value.sourceTag,
            ) { page, total ->
                _search.value = _search.value.copy(busy = "এপিসোড তালিকা আনছি… ($page/$total)")
            }

            // Many uploads exist per episode; only the best one is worth showing.
            val best = bestPerEpisode(entries)
            val options = best.entries.sortedBy { it.key }
                .map { EpisodeOption(it.key, it.value) }

            _search.value = _search.value.copy(
                busy = null,
                episodes = options,
                selected = emptySet(),
                remaining = client.remainingDownloads(),
                error = if (options.isEmpty()) {
                    "সিজন ${state.season}-এ ${languageName(settings.value.sourceTag)} সাবটাইটেল পাওয়া গেল না।"
                } else null,
            )
        } catch (e: Exception) {
            _search.value = _search.value.copy(busy = null, error = e.message)
        }
    }

    fun toggleEpisode(episode: Int) {
        val current = _search.value.selected
        _search.value = _search.value.copy(
            selected = if (episode in current) current - episode else current + episode,
        )
    }

    fun selectAllEpisodes() {
        _search.value = _search.value.copy(
            selected = _search.value.episodes.map { it.episode }.toSet(),
        )
    }

    fun clearEpisodeSelection() { _search.value = _search.value.copy(selected = emptySet()) }

    /**
     * Downloads the chosen episodes into the translation queue.
     *
     * Each one spends a download from the daily quota, so the run stops the
     * moment the server says the quota is gone and reports how far it got —
     * rather than hammering a limit that will not move until tomorrow.
     */
    fun downloadSelected() = viewModelScope.launch {
        val client = openSubtitles() ?: return@launch
        val state = _search.value
        val chosen = state.episodes.filter { it.episode in state.selected }
        if (chosen.isEmpty()) return@launch

        var done = 0
        var stoppedBy: String? = null
        var remaining = state.remaining

        for ((index, option) in chosen.withIndex()) {
            _search.value = _search.value.copy(
                busy = "নামাচ্ছি ${index + 1}/${chosen.size}…",
                error = null,
            )
            try {
                val ticket = client.requestDownload(option.entry.fileId)
                remaining = ticket.remaining
                val text = client.fetchText(ticket.link)
                addContent(ticket.fileName, text)
                done++
            } catch (e: OpenSubtitles.ApiException) {
                stoppedBy = e.message
                if (e.quotaExhausted || e.status == 403 || e.status == 401) break
            } catch (e: Exception) {
                stoppedBy = e.message
            }
        }

        _search.value = _search.value.copy(busy = null, remaining = remaining, error = stoppedBy)
        _notice.value = buildString {
            append("$done টি সাবটাইটেল অনুবাদের তালিকায় যোগ হয়েছে।")
            if (remaining != null) append(" আজ আর $remaining টি নামানো যাবে।")
            if (done < chosen.size && stoppedBy != null) append(" বাকিগুলো হয়নি: $stoppedBy")
        }
    }

    /**
     * Imports a subtitle pack. One archive can hold a whole season, which is
     * the only way to get three hundred episodes without three hundred
     * downloads counting against the daily limit.
     */
    fun importZip(uri: Uri) = viewModelScope.launch {
        _busyNote.value = "আর্কাইভ খুলছি…"
        try {
            val result = withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use {
                    ZipImport.extract(it)
                }
            }
            _busyNote.value = null

            if (result == null || result.files.isEmpty()) {
                _notice.value = "আর্কাইভে কোনো সাবটাইটেল ফাইল পাওয়া গেল না।"
                return@launch
            }
            for (file in result.files) addContent(file.fileName, file.text)
            _notice.value = "${result.files.size} টি ফাইল যোগ হয়েছে" +
                if (result.skipped > 0) ", ${result.skipped} টি বাদ পড়েছে।" else "।"
        } catch (e: Exception) {
            _busyNote.value = null
            _notice.value = "আর্কাইভটা পড়া গেল না: ${e.message ?: "অজানা সমস্যা"}"
        }
    }

    /**
     * Adds every subtitle in a picked folder, however deeply nested.
     *
     * Each file remembers where it sat relative to the folder, so a ZIP export
     * can hand the same tree back rather than a flat heap of files.
     */
    fun importFolder(treeUri: Uri) = viewModelScope.launch {
        _busyNote.value = "ফোল্ডার ঘুরে দেখছি…"
        try {
            val context = getApplication<Application>()
            val files = FolderScan.scan(context, treeUri)
            if (files.isEmpty()) {
                _notice.value = "ওই ফোল্ডারে কোনো সাবটাইটেল ফাইল পাওয়া গেল না।"
                return@launch
            }

            var added = 0
            withContext(Dispatchers.IO) {
                for ((index, file) in files.withIndex()) {
                    _busyNote.value = "পড়ছি ${index + 1}/${files.size}…"
                    val bytes = runCatching {
                        context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
                    }.getOrNull() ?: continue

                    val text = SubtitleText.decode(bytes).text
                    if (addContent(file.name, text, file.relativeDir, id = file.uri.toString())) {
                        added++
                    }
                }
            }
            _notice.value = "$added টি ফাইল যোগ হয়েছে (${files.size} টির মধ্যে)।"
        } catch (e: Exception) {
            _notice.value = "ফোল্ডারটা পড়া গেল না: ${e.message ?: "অজানা সমস্যা"}"
        } finally {
            _busyNote.value = null
        }
    }

    /** Writes every finished file into one archive, folder structure intact. */
    fun exportZip(target: Uri) = viewModelScope.launch {
        val done = _jobs.value.filter { it.status in EXPORTABLE }
        if (done.isEmpty()) {
            _notice.value = "এখনো সেভ করার মতো কোনো ফাইল নেই।"
            return@launch
        }

        _busyNote.value = "আর্কাইভ বানাচ্ছি…"
        try {
            val entries = done.mapNotNull { job ->
                val text = renderFile(job.id) ?: return@mapNotNull null
                val name = outputNameFor(job.fileName)
                val path = if (job.relativeDir.isEmpty()) name else "${job.relativeDir}/$name"
                ZipExport.Entry(path, text)
            }

            val written = withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openOutputStream(target)?.use { out ->
                    ZipExport.write(out, entries)
                } ?: 0
            }
            _notice.value = if (written > 0) "$written টি ফাইল ZIP-এ সেভ হয়েছে।"
            else "আর্কাইভে কিছু লেখা গেল না।"
        } catch (e: Exception) {
            _notice.value = "ZIP বানানো গেল না: ${e.message ?: "অজানা সমস্যা"}"
        } finally {
            _busyNote.value = null
        }
    }

    fun suggestedZipName(): String {
        val stem = (_series.value.ifBlank { "subtitles" }).replace(Regex("""[\\/:*?"<>|]"""), "")
        return "$stem.${settings.value.targetTag}.zip"
    }

    /** Puts subtitle text that came from anywhere but a file picker into the queue. */
    private fun addContent(fileName: String, text: String) {
        addContent(fileName, text, "", "content:$fileName:${text.length}")
    }

    private fun addContent(
        fileName: String,
        text: String,
        relativeDir: String,
        id: String,
    ): Boolean {
        if (parsed.containsKey(id)) return false

        val sub = runCatching { parse(fileName, text) }.getOrNull()
        if (sub == null || sub.cues.isEmpty()) {
            _jobs.value += JobUi(id, fileName, JobStatus.Error, error = "পড়া গেল না")
            return false
        }

        // Packs routinely carry the same episode twice under different names.
        val fingerprint = fingerprintOf(sub)
        if (contentFingerprints.containsKey(fingerprint)) return false
        contentFingerprints[fingerprint] = id

        parsed[id] = sub
        _jobs.value += JobUi(
            id = id,
            fileName = fileName,
            status = JobStatus.Queued,
            total = sub.cues.size,
            relativeDir = relativeDir,
        )
        if (_series.value.isBlank()) _series.value = guessSeries(fileName)
        return true
    }

    /* ------------------------------------------------------ file actions */

    fun removeJob(id: String) {
        if (_running.value) return
        parsed.remove(id)
        flaggedIds.remove(id)
        contentFingerprints.entries.removeIf { it.value == id }
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
        return outputNameFor(job.fileName)
    }

    /**
     * What a saved file should be called. Keeping the original name is what
     * makes a player pick the subtitle up automatically, because it only does
     * so when the names match the video file exactly.
     */
    private fun outputNameFor(fileName: String): String =
        if (settings.value.keepOriginalName) fileName
        else outputName(fileName, settings.value.targetTag)

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

    /**
     * Which files belong in an export. A passthrough file was already in the
     * target language and was never translated — but it is still one of the
     * files the user handed in, so it goes to the output unchanged.
     */
    private val EXPORTABLE = setOf(JobStatus.Done, JobStatus.Passthrough)

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
