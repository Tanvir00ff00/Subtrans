package com.subtrans.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.subtrans.app.ui.CueView
import com.subtrans.app.ui.MainViewModel

/**
 * Reading — and fixing — one subtitle file inside the app.
 *
 * Before a run this is how you check you picked the right file; after one it is
 * how you check the translation before saving it. Lines the offline engine was
 * unsure about are marked and can be isolated with the filter, which is usually
 * the only part worth reading closely. Any line can be corrected by hand, and a
 * corrected line stops counting as suspicious.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileViewerScreen(vm: MainViewModel, jobId: String, modifier: Modifier = Modifier) {
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val job = jobs.firstOrNull { it.id == jobId }

    var onlyFlagged by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Int?>(null) }

    // Cues are mutated in place, so the snapshot is rebuilt whenever the job
    // moves: progress, a polish pass, or a hand edit bumping its revision.
    val cues = remember(jobId, job?.done, job?.status, job?.polished, job?.revision) {
        vm.cuesOf(jobId)
    }

    val shown = cues.filter { cue ->
        (!onlyFlagged || cue.flagged) &&
            (query.isBlank() ||
                cue.source.contains(query, ignoreCase = true) ||
                cue.translated?.contains(query, ignoreCase = true) == true)
    }
    val translatedCount = cues.count { !it.translated.isNullOrBlank() }
    val flaggedCount = cues.count { it.flagged }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = vm::closeFile) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "ফিরে যাও")
                    }
                },
                title = {
                    Column {
                        Text(
                            job?.fileName ?: "ফাইল",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            buildString {
                                append("${cues.size} লাইন")
                                if (translatedCount > 0) append(" · $translatedCount অনুবাদ")
                                if (flaggedCount > 0) append(" · $flaggedCount সন্দেহজনক")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        searching = !searching
                        if (!searching) query = ""
                    }) {
                        Icon(
                            if (searching) Icons.Default.Close else Icons.Default.Search,
                            if (searching) "খোঁজা বন্ধ" else "খোঁজো",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (searching) {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("এই ফাইলে খোঁজো") },
                        singleLine = true,
                        supportingText = { Text("${shown.size} টি লাইন মিলেছে") },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                }
            }

            if (flaggedCount > 0) {
                item {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(vertical = 6.dp),
                    ) {
                        FilterChip(
                            selected = !onlyFlagged,
                            onClick = { onlyFlagged = false },
                            label = { Text("সব (${cues.size})") },
                        )
                        FilterChip(
                            selected = onlyFlagged,
                            onClick = { onlyFlagged = true },
                            label = { Text("সন্দেহজনক ($flaggedCount)") },
                        )
                    }
                }
            }

            if (shown.isEmpty()) {
                item {
                    Text(
                        if (query.isBlank()) "দেখানোর মতো কোনো লাইন নেই।"
                        else "\"$query\" কোথাও পাওয়া গেল না।",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            }

            items(shown, key = { it.id }) { cue ->
                if (editing == cue.id) {
                    CueEditor(
                        cue = cue,
                        onSave = { text ->
                            vm.editCue(jobId, cue.id, text)
                            editing = null
                        },
                        onReset = {
                            vm.resetCue(jobId, cue.id)
                            editing = null
                        },
                        onCancel = { editing = null },
                    )
                } else {
                    CueRow(cue, onEdit = { editing = cue.id })
                }
            }

            item { Box(Modifier.size(24.dp).fillMaxWidth()) }
        }
    }
}

@Composable
private fun CueRow(cue: CueView, onEdit: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (cue.flagged) scheme.surfaceVariant else Color.Transparent)
            .clickable(onClick = onEdit)
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Column(Modifier.width(52.dp), horizontalAlignment = Alignment.Start) {
            Text(
                cue.number.toString(),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.outline,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                cue.time,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.outline,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
            )
        }

        Column(Modifier.weight(1f)) {
            // The source stays visible even after translation, because judging a
            // translated line without the original in front of you is guesswork.
            Text(cue.source, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            if (!cue.translated.isNullOrBlank()) {
                Text(
                    cue.translated,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (cue.flagged) {
                Text(
                    "ইঞ্জিন এই লাইনটা নিয়ে নিশ্চিত নয় — ট্যাপ করে ঠিক করো",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFBBF24),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun CueEditor(
    cue: CueView,
    onSave: (String) -> Unit,
    onReset: () -> Unit,
    onCancel: () -> Unit,
) {
    var draft by remember(cue.id) { mutableStateOf(cue.translated.orEmpty()) }

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(10.dp),
    ) {
        Text(
            "${cue.number} · ${cue.time}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            cue.source,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("অনুবাদ") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { onSave(draft) }) { Text("সেভ") }
            TextButton(onClick = onCancel) { Text("বাতিল") }
            TextButton(onClick = onReset) { Text("মূল ভাষায় ফেরাও") }
        }
    }
}
