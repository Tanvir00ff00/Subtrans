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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.subtrans.app.engine.TranslationEngine
import com.subtrans.app.ui.LANGUAGE_NAMES
import com.subtrans.app.ui.MainViewModel
import com.subtrans.app.ui.languageName

@Composable
fun SettingsScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    var showKey by remember { mutableStateOf(false) }
    val downloaded by vm.downloadedModels.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.refreshDownloadedModels() }

    // Only offer languages the offline engine can actually handle.
    val languages = remember {
        LANGUAGE_NAMES.filterKeys { TranslationEngine.supports(it) }.toList().sortedBy { it.second }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Section("কোন ভাষা থেকে", "যে ভাষায় সাবটাইটেল ফাইলগুলো আছে") {
                LanguageChips(languages, settings.sourceTag) { tag ->
                    vm.updateSettings { it.copy(sourceTag = tag) }
                }
            }
        }

        item {
            Section("কোন ভাষায়", "যে ভাষায় অনুবাদ চাও") {
                LanguageChips(languages, settings.targetTag) { tag ->
                    vm.updateSettings { it.copy(targetTag = tag) }
                }
            }
        }

        item {
            Section(
                "শুধু ওয়াই-ফাইতে মডেল নামাও",
                "ভাষার মডেল ৩০-৪০ MB, একবারই নামে",
            ) {
                Switch(
                    checked = settings.requireWifiForModels,
                    onCheckedChange = { on -> vm.updateSettings { it.copy(requireWifiForModels = on) } },
                )
            }
        }

        item {
            Section(
                "AI পলিশ",
                "অফলাইন ইঞ্জিন যে অল্প কিছু লাইন নিয়ে সন্দিহান, শুধু সেগুলোই AI-কে পাঠানো হয়। " +
                    "পুরো এপিসোড কখনো যায় না, তাই কোটা প্রায় খরচই হয় না।",
            ) {
                Switch(
                    checked = settings.aiPolish,
                    onCheckedChange = { on -> vm.updateSettings { it.copy(aiPolish = on) } },
                )
            }
        }

        if (settings.aiPolish) {
            item {
                Column {
                    OutlinedTextField(
                        value = settings.geminiKey,
                        onValueChange = { key -> vm.updateSettings { it.copy(geminiKey = key) } },
                        label = { Text("Gemini API key") },
                        singleLine = true,
                        visualTransformation = if (showKey) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        supportingText = {
                            Text("key শুধু এই ফোনে থাকে, সরাসরি Google-এ যায়")
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = { showKey = !showKey }) {
                        Text(if (showKey) "লুকাও" else "দেখাও")
                    }

                    OutlinedTextField(
                        value = settings.geminiModel,
                        onValueChange = { m -> vm.updateSettings { it.copy(geminiModel = m) } },
                        label = { Text("মডেল") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Box(Modifier.size(8.dp))
                    Text(
                        "প্রতি এপিসোডে সর্বোচ্চ ${settings.maxPolishLines} টি লাইন AI-তে যাবে",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = settings.maxPolishLines.toFloat(),
                        onValueChange = { v ->
                            vm.updateSettings { it.copy(maxPolishLines = v.toInt()) }
                        },
                        valueRange = 0f..200f,
                        steps = 19,
                    )
                }
            }

            item {
                OutlinedTextField(
                    value = settings.tone,
                    onValueChange = { t -> vm.updateSettings { it.copy(tone = t) } },
                    label = { Text("ভাষার ধরন") },
                    supportingText = { Text("AI-কে বলা হবে ঠিক এই ঢঙে ঠিক করতে") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        item {
            Column(Modifier.padding(top = 14.dp)) {
                Text("OpenSubtitles", style = MaterialTheme.typography.titleSmall)
                Text(
                    "\"খোঁজো\" ট্যাবে সাবটাইটেল খুঁজতে ও নামাতে একটা ফ্রি API key লাগে — " +
                        "opensubtitles.com-এ অ্যাকাউন্ট খুলে Consumers পাতা থেকে নেওয়া যায়।",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = settings.osApiKey,
                    onValueChange = { k -> vm.updateSettings { it.copy(osApiKey = k) } },
                    label = { Text("API key") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                OutlinedTextField(
                    value = settings.osToken,
                    onValueChange = { t -> vm.updateSettings { it.copy(osToken = t) } },
                    label = { Text("টোকেন (ঐচ্ছিক)") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    supportingText = {
                        Text(
                            "লগ-ইন করা অ্যাকাউন্টে দৈনিক সীমা বেশি। অ্যাপ কখনো তোমার পাসওয়ার্ড " +
                                "চায় না বা রাখে না — টোকেন নিজে নিয়ে এখানে বসাতে পারো।",
                        )
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        }

        item {
            Section(
                "অরিজিনাল নামেই সেভ করো",
                "নাম অপরিবর্তিত থাকবে — ভিডিও ফাইলের সাথে মিলে গেলে প্লেয়ার নিজেই সাবটাইটেল তুলে নেয়। " +
                    "বন্ধ থাকলে নামের শেষে ভাষার ট্যাগ বসে, যেমন .bn",
            ) {
                Switch(
                    checked = settings.keepOriginalName,
                    onCheckedChange = { on -> vm.updateSettings { it.copy(keepOriginalName = on) } },
                )
            }
        }

        item {
            Section(
                "দ্বিভাষিক ফাইল",
                "প্রতিটা অনুবাদের নিচে মূল লাইনটাও থাকবে — ভাষা শেখার সময় কাজে দেয়",
            ) {
                Switch(
                    checked = settings.bilingual,
                    onCheckedChange = { on -> vm.updateSettings { it.copy(bilingual = on) } },
                )
            }
        }

        item {
            Section(
                "সেভ করার সময় পরিষ্কার করো",
                "খালি লাইন আর পরপর একই লাইনের পুনরাবৃত্তি বাদ পড়বে",
            ) {
                Switch(
                    checked = settings.tidyOnSave,
                    onCheckedChange = { on -> vm.updateSettings { it.copy(tidyOnSave = on) } },
                )
            }
        }

        item {
            Column(Modifier.padding(top = 14.dp)) {
                Text("নামানো ভাষার মডেল", style = MaterialTheme.typography.titleSmall)
                Text(
                    "প্রতিটা মডেল ৩০-৪০ MB জায়গা নেয়। যেটা আর লাগবে না, মুছে ফেলতে পারো।",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (downloaded.isEmpty()) {
                    Text(
                        "এখনো কিছু নামানো হয়নি।",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                downloaded.forEach { tag ->
                    Card(
                        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 12.dp),
                        ) {
                            Text(
                                languageName(tag),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { vm.deleteModel(tag) }) { Text("মুছো") }
                        }
                    }
                }
            }
        }

        item {
            Column {
                Text(
                    "একসাথে ${settings.concurrency} টি লাইন অনুবাদ",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    "ফোন গরম হলে বা ধীর লাগলে কমিয়ে দাও",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = settings.concurrency.toFloat(),
                    onValueChange = { v -> vm.updateSettings { it.copy(concurrency = v.toInt()) } },
                    valueRange = 1f..8f,
                    steps = 6,
                )
            }
        }

        item { Box(Modifier.size(32.dp).fillMaxWidth()) }
    }
}

@Composable
private fun LanguageChips(
    languages: List<Pair<String, String>>,
    selected: String,
    onPick: (String) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()),
    ) {
        languages.forEach { (tag, name) ->
            FilterChip(
                selected = tag == selected,
                onClick = { onPick(tag) },
                label = { Text(name) },
            )
        }
    }
}

@Composable
private fun Section(
    title: String,
    hint: String,
    control: @Composable () -> Unit,
) {
    Column(Modifier.padding(top = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!isWide(title)) control()
        }
        if (isWide(title)) {
            Box(Modifier.size(6.dp))
            control()
        }
    }
}

/** Language pickers need the full width; switches sit beside their label. */
private fun isWide(title: String) = title.startsWith("কোন ভাষা")

