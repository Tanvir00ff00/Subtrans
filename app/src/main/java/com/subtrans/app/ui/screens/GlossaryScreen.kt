package com.subtrans.app.ui.screens

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.subtrans.app.engine.GlossaryEntry
import com.subtrans.app.ui.MainViewModel

@Composable
fun GlossaryScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val glossaries by vm.glossaries.collectAsStateWithLifecycle()
    val activeSeries by vm.series.collectAsStateWithLifecycle()

    val names = remember(glossaries) { glossaries.keys.sorted() }
    var selected by remember { mutableStateOf(activeSeries) }

    LaunchedEffect(names, activeSeries) {
        if (selected !in names) selected = activeSeries.takeIf { it in names } ?: names.firstOrNull().orEmpty()
    }

    val entries = glossaries[selected].orEmpty()

    fun update(index: Int, entry: GlossaryEntry) {
        vm.setGlossary(selected, entries.mapIndexed { i, e -> if (i == index) entry else e })
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "এখানকার প্রতিটা শব্দ অনুবাদের আগেই লুকিয়ে ফেলা হয়, তাই ইঞ্জিন নামগুলোয় হাত দিতে পারে না। " +
                    "একবার বানান ঠিক করে দিলে সব এপিসোডে সেটাই থাকবে।",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        if (names.isNotEmpty()) {
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    names.forEach { name ->
                        FilterChip(
                            selected = name == selected,
                            onClick = { selected = name },
                            label = { Text("$name  ${glossaries[name]?.size ?: 0}") },
                        )
                    }
                }
            }
        }

        if (names.isEmpty()) {
            item {
                Text(
                    "এখনো কোনো গ্লসারি নেই। অনুবাদ ট্যাবে একটা ফাইল দিয়ে \"গ্লসারি বানাও\" চাপলে " +
                        "চরিত্রের নাম আর বিশেষ শব্দগুলো নিজে থেকেই এখানে চলে আসবে।",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }

        itemsIndexed(entries) { index, entry ->
            Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = entry.source,
                            onValueChange = { update(index, entry.copy(source = it)) },
                            label = { Text("মূল") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Box(Modifier.size(8.dp))
                        OutlinedTextField(
                            value = entry.target,
                            onValueChange = { update(index, entry.copy(target = it)) },
                            label = { Text("যা লেখা হবে") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = {
                            vm.setGlossary(selected, entries.filterIndexed { i, _ -> i != index })
                        }) {
                            Icon(Icons.Default.Close, "মুছে ফেলো")
                        }
                    }
                    if (entry.note.isNotBlank()) {
                        Text(
                            entry.note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                        )
                    }
                }
            }
        }

        if (selected.isNotBlank()) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { vm.setGlossary(selected, entries + GlossaryEntry("", "")) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                        Text("  নতুন শব্দ")
                    }
                    if (entries.isNotEmpty()) {
                        TextButton(
                            onClick = { vm.removeGlossary(selected) },
                            modifier = Modifier.weight(1f),
                        ) { Text("তালিকা মুছো") }
                    }
                }
            }
        }

        item { Box(Modifier.size(24.dp).fillMaxWidth()) }
    }
}
