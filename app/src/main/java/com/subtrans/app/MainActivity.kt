package com.subtrans.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.subtrans.app.ui.MainViewModel
import com.subtrans.app.ui.SubTransTheme
import com.subtrans.app.ui.screens.FileViewerScreen
import com.subtrans.app.ui.screens.GlossaryScreen
import com.subtrans.app.ui.screens.RulesScreen
import com.subtrans.app.ui.screens.SettingsScreen
import com.subtrans.app.ui.screens.TranslateScreen

private enum class Tab(val label: String, val icon: ImageVector) {
    Translate("অনুবাদ", Icons.Default.Translate),
    Glossary("গ্লসারি", Icons.Default.Book),
    Rules("নিয়ম", Icons.Default.FindReplace),
    Settings("সেটিংস", Icons.Default.Settings),
}

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SubTransTheme {
                AppScaffold(viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(vm: MainViewModel) {
    var tab by remember { mutableStateOf(Tab.Translate) }
    val openJobId by vm.openJobId.collectAsStateWithLifecycle()

    // Reading a file takes over the whole screen; system back closes it.
    openJobId?.let { id ->
        BackHandler { vm.closeFile() }
        FileViewerScreen(vm, id)
        return
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("SubTrans") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = { Icon(entry.icon, entry.label) },
                        label = { Text(entry.label) },
                    )
                }
            }
        },
    ) { padding ->
        val inner = Modifier.padding(padding)
        when (tab) {
            Tab.Translate -> TranslateScreen(vm, inner)
            Tab.Glossary -> GlossaryScreen(vm, inner)
            Tab.Rules -> RulesScreen(vm, inner)
            Tab.Settings -> SettingsScreen(vm, inner)
        }
    }
}
