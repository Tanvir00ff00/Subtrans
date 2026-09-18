package com.subtrans.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.subtrans.app.subtitle.FileLabel
import com.subtrans.app.ui.JobStatus
import com.subtrans.app.ui.JobUi
import com.subtrans.app.ui.MainViewModel
import com.subtrans.app.ui.ModelState
import com.subtrans.app.ui.RunStats
import com.subtrans.app.ui.languageName
import kotlin.math.roundToInt

/** Which slice of a long queue the user is looking at. */
private enum class QueueFilter(val label: String) {
    All("সব"),
    Pending("বাকি"),
    Done("শেষ"),
    Flagged("সন্দেহজনক"),
    Problems("সমস্যা"),
}

/** Characters of file name a row can show before it has to be shortened. */
private const val NAME_BUDGET = 46

@Composable
fun TranslateScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val series by vm.series.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val modelState by vm.modelState.collectAsStateWithLifecycle()
    val busyNote by vm.busyNote.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val glossaries by vm.glossaries.collectAsStateWithLifecycle()
    val stats by vm.lastStats.collectAsStateWithLifecycle()

    LaunchedEffect(settings.sourceTag, settings.targetTag) { vm.refreshModelState() }

    var savingId by remember { mutableStateOf<String?>(null) }
    var shiftingId by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf(QueueFilter.All) }
    var query by remember { mutableStateOf("") }
    var confirmClear by remember { mutableStateOf(false) }

    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> vm.addFiles(uris) }

    val pickImportFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> if (uri != null) vm.importFolder(uri) }

    val pickZip = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) vm.importZip(uri) }

    val pickExportFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> if (uri != null) vm.exportAll(uri) }

    val saveZip = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> if (uri != null) vm.exportZip(uri) }

    val saveOne = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-subrip"),
    ) { uri ->
        val id = savingId
        if (uri != null && id != null) vm.exportOne(id, uri)
        savingId = null
    }

    val counts = remember(jobs) { QueueCounts.of(jobs) }
    val visible = remember(jobs, filter, query) {
        val byFilter = jobs.filter { job ->
            when (filter) {
                QueueFilter.All -> true
                QueueFilter.Pending ->
                    job.status == JobStatus.Queued || job.status == JobStatus.Stopped
                QueueFilter.Done ->
                    job.status == JobStatus.Done || job.status == JobStatus.Passthrough
                QueueFilter.Flagged -> job.flagged > 0
                QueueFilter.Problems -> job.status == JobStatus.Error || job.warning != null
            }
        }
        val byQuery =
            if (query.isBlank()) byFilter
            else byFilter.filter { it.fileName.contains(query, ignoreCase = true) }
        // Natural order, so E2 comes before E10 rather than after it.
        FileLabel.sortNaturally(byQuery) { it.fileName }
    }

    shiftingId?.let { id ->
        TimingShiftDialog(
            fileName = jobs.firstOrNull { it.id == id }?.fileName.orEmpty(),
            onShift = { ms ->
                vm.shiftTiming(id, ms)
                shiftingId = null
            },
            onDismiss = { shiftingId = null },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("তালিকা খালি করবে?") },
            text = {
                Text(
                    "${jobs.size} টি ফাইল সরে যাবে। যেগুলো অনুবাদ হয়েছে কিন্তু এখনো সেভ করোনি, " +
                        "সেগুলোও চলে যাবে।"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearJobs()
                    confirmClear = false
                }) { Text("খালি করো") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("থাক") } },
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ModelBanner(
                state = modelState,
                auto = settings.autoDetectSource,
                source = languageName(settings.sourceTag),
                target = languageName(settings.targetTag),
                onDownload = vm::downloadModels,
            )
        }

        item {
            OutlinedTextField(
                value = series,
                onValueChange = vm::setSeries,
                label = { Text("সিরিজের নাম") },
                supportingText = {
                    val size = glossaries[series]?.size ?: 0
                    Text(
                        if (size > 0) "গ্লসারিতে $size টি শব্দ — প্রতিটা অনুবাদে ব্যবহার হবে"
                        else "গ্লসারি এই নামে সেভ হয়"
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item {
            ImportRow(
                running = running,
                onFiles = { pickFiles.launch(arrayOf("*/*")) },
                onFolder = { pickImportFolder.launch(null) },
                onZip = {
                    pickZip.launch(
                        arrayOf("application/zip", "application/x-zip-compressed", "*/*")
                    )
                },
            )
        }

        item {
            if (running) {
                Button(onClick = vm::stop, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Stop, null, Modifier.size(18.dp))
                    Text("  থামাও")
                }
            } else {
                Button(
                    onClick = vm::start,
                    enabled = counts.pending > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                    Text(
                        if (counts.pending > 0) "  অনুবাদ করো (${counts.pending})"
                        else "  অনুবাদ করো"
                    )
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = vm::buildGlossary,
                    enabled = !running && jobs.isNotEmpty() && busyNote == null,
                    modifier = Modifier.weight(1f),
                ) { Text("গ্লসারি") }

                OutlinedButton(
                    onClick = { pickExportFolder.launch(null) },
                    enabled = counts.exportable > 0 && !running,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Save, null, Modifier.size(18.dp))
                    Text("  সেভ (${counts.exportable})")
                }

                OutlinedButton(
                    onClick = { saveZip.launch(vm.suggestedZipName()) },
                    enabled = counts.exportable > 0 && !running,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.FolderZip, null, Modifier.size(18.dp))
                    Text("  ZIP")
                }
            }
        }

        busyNote?.let { note ->
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(note, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        notice?.let { text ->
            item {
                Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(12.dp)) {
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = vm::dismissNotice) { Text("ঠিক আছে") }
                    }
                }
            }
        }

        stats?.let { if (!running) item { StatsCard(it) } }

        if (jobs.isEmpty()) {
            item {
                Text(
                    "উপর থেকে ফাইল, ফোল্ডার বা ZIP নাও। ফোল্ডারের ভেতরের ফোল্ডারও খুঁজে দেখা হয়। " +
                        "যোগ করার পর যেকোনো ফাইলে ট্যাপ করলে ভেতরটা পড়া যায়।",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        } else {
            item { QueueHeader(counts, running = running, onClear = { confirmClear = true }) }

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    QueueFilter.entries.forEach { option ->
                        val n = counts.of(option)
                        FilterChip(
                            selected = filter == option,
                            onClick = { filter = option },
                            enabled = n > 0 || option == QueueFilter.All,
                            label = { Text("${option.label} $n") },
                        )
                    }
                }
            }

            if (jobs.size > 12) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("এই তালিকায় খোঁজো") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (visible.isEmpty()) {
                item {
                    Text(
                        "এই ফিল্টারে কিছু নেই।",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                }
            }

            items(visible, key = { it.id }) { job ->
                JobRow(
                    job = job,
                    busy = running,
                    onOpen = { vm.openFile(job.id) },
                    onSave = {
                        savingId = job.id
                        saveOne.launch(vm.suggestedFileName(job.id))
                    },
                    onShift = { shiftingId = job.id },
                    onTidy = { vm.tidyFile(job.id) },
                    onDetect = { vm.detectSourceLanguage(job.id) },
                    onRemove = { vm.removeJob(job.id) },
                )
            }
        }

        item { Box(Modifier.size(24.dp)) }
    }
}

/* ------------------------------------------------------------- counting */

private data class QueueCounts(
    val total: Int,
    val pending: Int,
    val done: Int,
    val passthrough: Int,
    val flagged: Int,
    val problems: Int,
    val lines: Int,
) {
    /** Files ready to write out: translated, plus those already in the target. */
    val exportable: Int get() = done + passthrough

    fun of(filter: QueueFilter): Int = when (filter) {
        QueueFilter.All -> total
        QueueFilter.Pending -> pending
        QueueFilter.Done -> exportable
        QueueFilter.Flagged -> flagged
        QueueFilter.Problems -> problems
    }

    companion object {
        fun of(jobs: List<JobUi>) = QueueCounts(
            total = jobs.size,
            pending = jobs.count {
                it.status == JobStatus.Queued || it.status == JobStatus.Stopped
            },
            done = jobs.count { it.status == JobStatus.Done },
            passthrough = jobs.count { it.status == JobStatus.Passthrough },
            flagged = jobs.count { it.flagged > 0 },
            problems = jobs.count { it.status == JobStatus.Error || it.warning != null },
            lines = jobs.sumOf { it.total },
        )
    }
}

@Composable
private fun QueueHeader(counts: QueueCounts, running: Boolean, onClear: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Column(Modifier.weight(1f)) {
            Text("সারি", style = MaterialTheme.typography.titleSmall)
            Text(
                buildString {
                    append("${counts.total} ফাইল · ${counts.lines} লাইন")
                    if (counts.passthrough > 0) append(" · ${counts.passthrough} অপরিবর্তিত")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onClear, enabled = !running) { Text("খালি করো") }
    }
}

/* ----------------------------------------------------------------- rows */

@Composable
private fun JobRow(
    job: JobUi,
    busy: Boolean,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onShift: () -> Unit,
    onTidy: () -> Unit,
    onDetect: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    // Across three hundred files of one series the episode is the only thing
    // that differs, so it leads. The full name is shortened from the middle,
    // never the end — the end is where the episode lives.
    val heading = remember(job.fileName) { FileLabel.heading(job.fileName) }
    val shortName = remember(job.fileName) { FileLabel.middleEllipsis(job.fileName, NAME_BUDGET) }

    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.clickable(enabled = job.total > 0, onClick = onOpen).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(job.status)

                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(
                        heading,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        shortName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 2,
                    )
                }

                Text(
                    when (job.status) {
                        JobStatus.Running -> "${(job.progress * 100).toInt()}%"
                        JobStatus.Done -> "✓"
                        JobStatus.Passthrough -> "অপরিবর্তিত"
                        else -> if (job.total > 0) "${job.total}" else "—"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Box {
                    IconButton(onClick = { menuOpen = true }, enabled = !busy) {
                        Icon(Icons.Default.MoreVert, "আরও")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("ভেতরটা দেখো") },
                            onClick = { menuOpen = false; onOpen() },
                            enabled = job.total > 0,
                        )
                        DropdownMenuItem(
                            text = { Text("এই ফাইলটা সেভ করো") },
                            onClick = { menuOpen = false; onSave() },
                            enabled = job.total > 0,
                        )
                        DropdownMenuItem(
                            text = { Text("টাইমিং সরাও") },
                            onClick = { menuOpen = false; onShift() },
                            enabled = job.total > 0,
                        )
                        DropdownMenuItem(
                            text = { Text("খালি ও পুনরাবৃত্ত লাইন বাদ দাও") },
                            onClick = { menuOpen = false; onTidy() },
                            enabled = job.total > 0,
                        )
                        DropdownMenuItem(
                            text = { Text("কোন ভাষা, শনাক্ত করো") },
                            onClick = { menuOpen = false; onDetect() },
                            enabled = job.total > 0,
                        )
                        DropdownMenuItem(
                            text = { Text("তালিকা থেকে সরাও") },
                            onClick = { menuOpen = false; onRemove() },
                        )
                    }
                }
            }

            if (job.status == JobStatus.Running) {
                LinearProgressIndicator(
                    progress = { job.progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }

            job.error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            job.warning?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFBBF24),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (job.sourceTag.isNotEmpty() && job.status != JobStatus.Passthrough) {
                Text(
                    "উৎস: ${languageName(job.sourceTag)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            if (job.status == JobStatus.Done && job.flagged > 0) {
                Text(
                    if (job.polished > 0) {
                        "${job.flagged} টি সন্দেহজনক, ${job.polished} টি AI ঠিক করেছে"
                    } else {
                        "${job.flagged} টি লাইন সন্দেহজনক — ট্যাপ করে দেখে নাও"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusDot(status: JobStatus) {
    val color = when (status) {
        JobStatus.Queued -> MaterialTheme.colorScheme.outline
        JobStatus.Running -> MaterialTheme.colorScheme.primary
        JobStatus.Done -> Color(0xFF4ADE80)
        JobStatus.Error -> MaterialTheme.colorScheme.error
        JobStatus.Stopped -> Color(0xFFFBBF24)
        JobStatus.Passthrough -> Color(0xFF38BDF8)
    }
    Surface(color = color, shape = CircleShape, modifier = Modifier.size(8.dp).clip(CircleShape)) {}
}

/* -------------------------------------------------------------- pieces */

@Composable
private fun ImportRow(
    running: Boolean,
    onFiles: () -> Unit,
    onFolder: () -> Unit,
    onZip: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onFiles, enabled = !running, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.InsertDriveFile, null, Modifier.size(18.dp))
            Text("  ফাইল")
        }
        OutlinedButton(onClick = onFolder, enabled = !running, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
            Text("  ফোল্ডার")
        }
        OutlinedButton(onClick = onZip, enabled = !running, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.FolderZip, null, Modifier.size(18.dp))
            Text("  ZIP")
        }
    }
}

@Composable
private fun StatsCard(stats: RunStats) {
    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp)) {
            Text("শেষ রান", style = MaterialTheme.typography.titleSmall)
            Text(
                buildString {
                    append("${stats.files} টি অনুবাদ · ${stats.lines} লাইন · ${stats.seconds} সেকেন্ড")
                    if (stats.passedThrough > 0) append(" · ${stats.passedThrough} অপরিবর্তিত")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (stats.languages.size > 1) {
                Text(
                    "ভাষা পাওয়া গেছে: " + stats.languages.joinToString(", ") { languageName(it) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "${(stats.offlineShare * 100).roundToInt()}% লাইন অফলাইনেই হয়েছে, " +
                    "${stats.flagged} টি সন্দেহজনক, ${stats.polished} টি AI ঠিক করেছে",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun TimingShiftDialog(
    fileName: String,
    onShift: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val steps = listOf(-2000L, -1000L, -500L, 500L, 1000L, 2000L)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("টাইমিং সরাও") },
        text = {
            Column {
                Text(
                    "সাবটাইটেল আগে বা পরে চললে পুরো ট্র্যাকটা সরিয়ে নাও। " +
                        "প্রতিটা টাইমস্ট্যাম্প সমান পরিমাণে সরবে।",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    FileLabel.middleEllipsis(fileName, 40),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 6.dp),
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    steps.take(3).forEach { ms ->
                        OutlinedButton(onClick = { onShift(ms) }, modifier = Modifier.weight(1f)) {
                            Text("%.1f".format(ms / 1000.0))
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    steps.drop(3).forEach { ms ->
                        OutlinedButton(onClick = { onShift(ms) }, modifier = Modifier.weight(1f)) {
                            Text("+%.1f".format(ms / 1000.0))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("বন্ধ") } },
    )
}

@Composable
private fun ModelBanner(
    state: ModelState,
    auto: Boolean,
    source: String,
    target: String,
    onDownload: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val pair = if (auto) "ভাষা নিজে থেকে শনাক্ত হবে → $target" else "$source → $target"

    when (state) {
        ModelState.Ready -> Text(
            "$pair · অফলাইনে প্রস্তুত",
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )

        ModelState.Downloading -> Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("ভাষার মডেল নামছে…", style = MaterialTheme.typography.bodyMedium)
        }

        ModelState.Unsupported -> Card(
            colors = CardDefaults.cardColors(scheme.errorContainer),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text(
                "$source → $target জোড়াটা অফলাইন ইঞ্জিন সাপোর্ট করে না। সেটিংসে ভাষা বদলাও।",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp),
            )
        }

        else -> Card(
            colors = CardDefaults.cardColors(scheme.surfaceVariant),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(pair, style = MaterialTheme.typography.titleSmall)
                Text(
                    "একবার ভাষার মডেল নামালে তারপর সব অনুবাদ অফলাইনে চলবে — কোনো লিমিট নেই, খরচ নেই।",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                TextButton(onClick = onDownload, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                    Text("  মডেল নামাও")
                }
            }
        }
    }
}
