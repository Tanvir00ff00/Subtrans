package com.subtrans.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.subtrans.app.ui.JobStatus
import com.subtrans.app.ui.JobUi
import com.subtrans.app.ui.MainViewModel
import com.subtrans.app.ui.ModelState
import com.subtrans.app.ui.RunStats
import com.subtrans.app.ui.languageName
import kotlin.math.roundToInt

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

    /** Which file a "save this one" dialog is currently targeting. */
    var savingId by remember { mutableStateOf<String?>(null) }
    var shiftingId by remember { mutableStateOf<String?>(null) }

    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> vm.addFiles(uris) }

    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> if (uri != null) vm.exportAll(uri) }

    val saveOne = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-subrip"),
    ) { uri ->
        val id = savingId
        if (uri != null && id != null) vm.exportOne(id, uri)
        savingId = null
    }

    val pending = jobs.count { it.status == JobStatus.Queued || it.status == JobStatus.Stopped }
    val finished = jobs.count { it.status == JobStatus.Done }
    val glossarySize = glossaries[series]?.size ?: 0

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

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ModelBanner(
                state = modelState,
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
                    Text(
                        if (glossarySize > 0) "এই সিরিজের গ্লসারিতে $glossarySize টি শব্দ আছে"
                        else "গ্লসারি এই নামে সেভ হয়"
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { pickFiles.launch(arrayOf("*/*")) },
                    enabled = !running,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.FolderOpen, null, Modifier.size(18.dp))
                    Text("  ফাইল বাছো")
                }

                if (running) {
                    Button(onClick = vm::stop, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Stop, null, Modifier.size(18.dp))
                        Text("  থামাও")
                    }
                } else {
                    Button(
                        onClick = vm::start,
                        enabled = pending > 0,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                        Text(if (pending > 0) "  অনুবাদ ($pending)" else "  অনুবাদ")
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = vm::buildGlossary,
                    enabled = !running && jobs.isNotEmpty() && busyNote == null,
                    modifier = Modifier.weight(1f),
                ) { Text("গ্লসারি বানাও") }

                OutlinedButton(
                    onClick = { pickFolder.launch(null) },
                    enabled = finished > 0 && !running,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.Save, null, Modifier.size(18.dp))
                    Text(if (finished > 0) "  সব সেভ ($finished)" else "  সব সেভ")
                }
            }
        }

        if (jobs.isEmpty()) {
            item {
                Text(
                    "উপরে \"ফাইল বাছো\" চেপে সাবটাইটেল ফাইল নাও — .srt, .vtt বা .ass। " +
                        "একসাথে পুরো সিজন দিতে পারো। ফাইলে ট্যাপ করলে ভেতরটা পড়া যায়।",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
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

        items(jobs, key = { it.id }) { job ->
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

        item { Box(Modifier.size(24.dp)) }
    }
}

/**
 * What the batch actually cost. The point of showing it is the offline share:
 * it makes visible how little of the work needed an AI call.
 */
@Composable
private fun StatsCard(stats: RunStats) {
    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp)) {
            Text("শেষ রান", style = MaterialTheme.typography.titleSmall)
            Text(
                "${stats.files} টি ফাইল · ${stats.lines} লাইন · ${stats.seconds} সেকেন্ড",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                    fileName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
    source: String,
    target: String,
    onDownload: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    when (state) {
        ModelState.Ready -> Text(
            "$source → $target · অফলাইনে প্রস্তুত",
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
                Text("$source → $target", style = MaterialTheme.typography.titleSmall)
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

    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.clickable(enabled = job.total > 0, onClick = onOpen).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(job.status)
                Text(
                    job.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 10.dp),
                )
                Text(
                    when (job.status) {
                        JobStatus.Running -> "${(job.progress * 100).toInt()}%"
                        JobStatus.Done -> "✓"
                        else -> if (job.total > 0) "${job.total} লাইন" else "—"
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

            if (job.status == JobStatus.Done && job.flagged > 0) {
                Text(
                    if (job.polished > 0) {
                        "${job.flagged} টি লাইন সন্দেহজনক ছিল, ${job.polished} টি AI ঠিক করেছে"
                    } else {
                        "${job.flagged} টি লাইন সন্দেহজনক — ট্যাপ করে দেখে নাও"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
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
    }
    Surface(color = color, shape = CircleShape, modifier = Modifier.size(8.dp).clip(CircleShape)) {}
}
