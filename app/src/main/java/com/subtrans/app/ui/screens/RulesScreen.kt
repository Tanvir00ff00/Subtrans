package com.subtrans.app.ui.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.subtrans.app.engine.ReplaceRule
import com.subtrans.app.ui.MainViewModel

/**
 * Find-and-replace the user owns.
 *
 * The glossary fixes names before translation; these rules fix everything
 * after it — a phrase the engine renders stiffly, a punctuation habit, a word
 * you simply prefer. They run on every line of every file, so one rule fixes a
 * whole series at once.
 */
@Composable
fun RulesScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val rules by vm.rules.collectAsStateWithLifecycle()

    fun update(index: Int, rule: ReplaceRule) =
        vm.setRules(rules.mapIndexed { i, r -> if (i == index) rule else r })

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "অনুবাদ শেষ হওয়ার পর এই নিয়মগুলো প্রতিটা লাইনে চলে। গ্লসারি নাম ঠিক রাখে, " +
                    "আর এগুলো ঠিক করে বাকি সব — একটা নিয়ম পুরো সিরিজে কাজ করে।",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        if (rules.isEmpty()) {
            item {
                Text(
                    "এখনো কোনো নিয়ম নেই। নিচে যোগ করো — যেমন \"ok\" বদলে \"ঠিক আছে\"।",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }

        itemsIndexed(rules) { index, rule ->
            Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = rule.find,
                            onValueChange = { update(index, rule.copy(find = it)) },
                            label = { Text("যা খুঁজবে") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Box(Modifier.size(8.dp))
                        OutlinedTextField(
                            value = rule.replace,
                            onValueChange = { update(index, rule.copy(replace = it)) },
                            label = { Text("যা বসাবে") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = {
                            vm.setRules(rules.filterIndexed { i, _ -> i != index })
                        }) {
                            Icon(Icons.Default.Close, "মুছে ফেলো")
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = rule.enabled,
                            onCheckedChange = { update(index, rule.copy(enabled = it)) },
                        )
                        Text(
                            "চালু",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 6.dp, end = 16.dp),
                        )
                        Switch(
                            checked = rule.regex,
                            onCheckedChange = { update(index, rule.copy(regex = it)) },
                        )
                        Text(
                            "রেগেক্স",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }

                    // A rule the user typed badly is skipped at run time rather
                    // than crashing the batch, but saying so here is kinder.
                    if (rule.regex && rule.find.isNotBlank() &&
                        runCatching { Regex(rule.find) }.isFailure
                    ) {
                        Text(
                            "এই রেগেক্সটা বৈধ নয় — নিয়মটা এড়িয়ে যাওয়া হবে।",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        item {
            OutlinedButton(
                onClick = { vm.setRules(rules + ReplaceRule("", "")) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp))
                Text("  নতুন নিয়ম")
            }
        }

        item { Box(Modifier.size(24.dp).fillMaxWidth()) }
    }
}
