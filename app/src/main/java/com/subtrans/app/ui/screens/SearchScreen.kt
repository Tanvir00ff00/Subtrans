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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.subtrans.app.ui.EpisodeOption
import com.subtrans.app.ui.MainViewModel
import com.subtrans.app.ui.languageName

/**
 * Finding subtitles without leaving the app.
 *
 * Two routes sit side by side on purpose. Searching OpenSubtitles is the
 * convenient one, but every episode spends one of a small daily allowance, so
 * the remaining count is shown before a bulk download rather than after.
 * Importing a season pack as a ZIP costs nothing at all, which is the only
 * practical way to get a long series in one go.
 */
@Composable
fun SearchScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val state by vm.search.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()

    val pickZip = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) vm.importZip(uri) }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            OutlinedButton(
                onClick = { pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*")) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Icon(Icons.Default.FolderZip, null, Modifier.size(18.dp))
                Text("  সিজন প্যাক (ZIP) ইমপোর্ট করো")
            }
        }

        item {
            Text(
                "একটা ZIP-এ গোটা সিজন থাকলে সেটা এক ডাউনলোডেই আসে — দৈনিক সীমার হিসাবে পড়ে না। " +
                    "নিচের সার্চে প্রতিটা এপিসোড আলাদা করে নামে, তাই সীমা লাগে।",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.show == null) {
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    OutlinedTextField(
                        value = state.query,
                        onValueChange = vm::setSearchQuery,
                        label = { Text("সিরিজের নাম") },
                        placeholder = { Text("যেমন: Boruto") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        onClick = vm::searchShows,
                        enabled = state.query.isNotBlank() && state.busy == null,
                        modifier = Modifier.padding(bottom = 8.dp),
                    ) {
                        Icon(Icons.Default.Search, null, Modifier.size(18.dp))
                    }
                }
            }
        } else {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = vm::backToShows) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "ফিরে যাও")
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            state.show!!.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "সিজন ${state.season} · ${languageName(settings.sourceTag)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = { vm.setSeason((state.season - 1).coerceAtLeast(1)) },
                        enabled = state.season > 1 && state.busy == null,
                    ) { Text("−") }
                    Text("সিজন ${state.season}", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(
                        onClick = { vm.setSeason(state.season + 1) },
                        enabled = state.busy == null,
                    ) { Text("+") }
                }
            }

            state.remaining?.let { left ->
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            if (left < state.selected.size) MaterialTheme.colorScheme.errorContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                    ) {
                        Text(
                            if (left < state.selected.size) {
                                "আজ আর $left টি নামানো যাবে, কিন্তু ${state.selected.size} টি বেছেছ — " +
                                    "প্রথম $left টির পর থেমে যাবে।"
                            } else {
                                "আজ আর $left টি সাবটাইটেল নামানো যাবে।"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            }

            if (state.episodes.isNotEmpty()) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = vm::selectAllEpisodes) {
                            Text("সব বাছো (${state.episodes.size})")
                        }
                        TextButton(onClick = vm::clearEpisodeSelection) { Text("কিছুই না") }
                    }
                }

                item {
                    Button(
                        onClick = vm::downloadSelected,
                        enabled = state.selected.isNotEmpty() && state.busy == null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                        Text("  নামাও (${state.selected.size})")
                    }
                }
            }
        }

        state.busy?.let { note ->
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

        state.error?.let { message ->
            item {
                Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
        }

        if (state.show == null) {
            items(state.shows, key = { it.featureId }) { show ->
                Card(
                    colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth().clickable { vm.pickShow(show) },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(show.title, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            listOfNotNull(
                                show.year,
                                show.seasons?.let { "$it সিজন" },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            items(state.episodes, key = { it.episode }) { option ->
                EpisodeRow(
                    option = option,
                    checked = option.episode in state.selected,
                    onToggle = { vm.toggleEpisode(option.episode) },
                )
            }
        }

        item { Box(Modifier.size(24.dp).fillMaxWidth()) }
    }
}

@Composable
private fun EpisodeRow(option: EpisodeOption, checked: Boolean, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle),
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
            Text(
                "পর্ব ${option.episode}" +
                    (option.entry.episodeTitle?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    if (option.entry.trusted) append("বিশ্বস্ত · ")
                    append("${option.entry.downloads} ডাউনলোড")
                    if (option.entry.hearingImpaired) append(" · শ্রবণসহায়ক")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
