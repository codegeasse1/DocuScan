package com.docuscan.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.docuscan.app.BuildConfig
import com.docuscan.app.DocViewModel
import com.docuscan.app.data.AppSettings
import com.docuscan.app.scan.FILTERS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(vm: DocViewModel, snackbar: SnackbarHostState) {
    val s = vm.settings
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val langLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val code = withContext(Dispatchers.IO) { vm.importOcrLang(uri) }
                if (code != null) {
                    vm.updateSettings(vm.settings.copy(ocrLang = code))
                    snackbar.showSnackbar("Imported OCR language '$code'")
                } else {
                    snackbar.showSnackbar("Couldn't import that language pack")
                }
            }
        }
    }

    val inboxLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
            }
            vm.updateSettings(vm.settings.copy(inboxUri = uri.toString(), inboxEnabled = true))
            snackbar.showSnackbar("Inbox folder set")
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
        )

        SectionTitle("Default filter for new pages")
        Text(
            "Applied to every new page. You can still change it per page in the editor.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(FILTERS) { f ->
                FilterChip(
                    selected = s.defaultFilter == f.id,
                    onClick = { vm.updateSettings(s.copy(defaultFilter = f.id)) },
                    label = { Text(f.label) }
                )
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        SectionTitle("Export format")
        RadioRow(
            label = "PDF + JPG",
            hint = "Best of both worlds",
            selected = s.format == "both",
            onClick = { vm.updateSettings(s.copy(format = "both")) }
        )
        RadioRow(
            label = "PDF only",
            hint = "Single multi-page file",
            selected = s.format == "pdf",
            onClick = { vm.updateSettings(s.copy(format = "pdf")) }
        )
        RadioRow(
            label = "JPG only",
            hint = "One image per page",
            selected = s.format == "jpg",
            onClick = { vm.updateSettings(s.copy(format = "jpg")) }
        )

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        SectionTitle("JPEG export")
        Text(
            "Quality: ${s.jpegQuality}%",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = s.jpegQuality.toFloat(),
            onValueChange = { vm.updateSettings(s.copy(jpegQuality = it.toInt())) },
            valueRange = 50f..100f
        )
        Row(Modifier.padding(bottom = 6.dp)) {
            FilterChip(
                selected = s.jpegColor,
                onClick = { vm.updateSettings(s.copy(jpegColor = true)) },
                label = { Text("Color") }
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = !s.jpegColor,
                onClick = { vm.updateSettings(s.copy(jpegColor = false)) },
                label = { Text("Black & white") }
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        SectionTitle("OCR language")
        Text(
            "OCR runs fully offline on-device. Import .traineddata language packs (e.g. from tessdata_fast) for more languages.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(vm.ocrLangs) { lang ->
                FilterChip(
                    selected = s.ocrLang == lang,
                    onClick = { vm.updateSettings(s.copy(ocrLang = lang)) },
                    label = { Text(lang) }
                )
            }
            item {
                OutlinedButton(onClick = { langLauncher.launch(arrayOf("*/*")) }) {
                    Text("Import pack")
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        SectionTitle("Inbox Mode")
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Automatically save every export to a folder you choose",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (s.inboxEnabled && s.inboxUri.isNotBlank()) {
                    Text(
                        "Folder set",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            Switch(
                checked = s.inboxEnabled,
                onCheckedChange = { on ->
                    if (on && s.inboxUri.isBlank()) {
                        inboxLauncher.launch(null)
                    } else {
                        vm.updateSettings(s.copy(inboxEnabled = on))
                    }
                }
            )
        }
        if (s.inboxEnabled && s.inboxUri.isNotBlank()) {
            OutlinedButton(onClick = { inboxLauncher.launch(null) }) {
                Text("Change folder")
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        SectionTitle("Accessibility Mode")
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Spoken feedback and haptics; use the volume buttons as the shutter in the camera.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = s.accessibilityEnabled,
                onCheckedChange = { vm.updateSettings(s.copy(accessibilityEnabled = it)) }
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        SectionTitle("Appearance")
        RadioRow(
            label = "System",
            hint = "Follow device theme",
            selected = s.theme == "system",
            onClick = { vm.updateSettings(s.copy(theme = "system")) }
        )
        RadioRow(
            label = "Light",
            hint = "Always light",
            selected = s.theme == "light",
            onClick = { vm.updateSettings(s.copy(theme = "light")) }
        )
        RadioRow(
            label = "Dark",
            hint = "Always dark",
            selected = s.theme == "dark",
            onClick = { vm.updateSettings(s.copy(theme = "dark")) }
        )

        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        SectionTitle("About")
        Row(Modifier.padding(vertical = 6.dp)) {
            Text("Version", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "DocuScan is a local-first document scanner: all scanning, OCR, filtering and export happens on your device. " +
                "Your documents stay yours.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun RadioRow(label: String, hint: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 8.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
