package com.docuscan.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.docuscan.app.ui.CameraScreen
import com.docuscan.app.ui.DocumentsScreen
import com.docuscan.app.ui.EditorScreen
import com.docuscan.app.ui.HomeScreen
import com.docuscan.app.ui.SettingsScreen
import com.docuscan.app.ui.theme.DocuScanTheme

@Composable
fun App(vm: DocViewModel = viewModel()) {
    val dark = when (vm.settings.theme) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    DocuScanTheme(darkTheme = dark) {
        val snackbarHostState = remember { SnackbarHostState() }

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (vm.screen == Screen.Tabs) {
                    BottomBar(vm)
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { padding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                when (val s = vm.screen) {
                    Screen.Camera -> CameraScreen(vm, snackbarHostState)
                    Screen.Editor -> EditorScreen(vm, snackbarHostState)
                    Screen.Tabs -> {
                        when (vm.tab) {
                            Tab.Home -> HomeScreen(vm, snackbarHostState)
                            Tab.Documents -> DocumentsScreen(vm, snackbarHostState)
                            Tab.Settings -> SettingsScreen(vm, snackbarHostState)
                        }
                    }
                }
            }
        }

        BackHandler(enabled = vm.screen != Screen.Tabs) {
            when (vm.screen) {
                Screen.Camera -> vm.closeCamera()
                Screen.Editor -> vm.selectTab(Tab.Home)
                else -> Unit
            }
        }
    }
}

@Composable
private fun BottomBar(vm: DocViewModel) {
    NavigationBar {
        NavigationBarItem(
            selected = vm.tab == Tab.Home,
            onClick = { vm.selectTab(Tab.Home) },
            icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
            label = { Text("Home") }
        )
        NavigationBarItem(
            selected = vm.tab == Tab.Documents,
            onClick = { vm.selectTab(Tab.Documents) },
            icon = { Icon(Icons.Default.List, contentDescription = "Documents") },
            label = { Text("Documents") }
        )
        NavigationBarItem(
            selected = vm.tab == Tab.Settings,
            onClick = { vm.selectTab(Tab.Settings) },
            icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
            label = { Text("Settings") }
        )
    }
}
