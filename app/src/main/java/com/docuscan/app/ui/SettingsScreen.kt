package com.docuscan.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.docuscan.app.DocViewModel
import com.docuscan.app.data.AppSettings
import com.docuscan.app.scan.FILTERS

@Composable
fun SettingsScreen(vm: DocViewModel) {
    val s = vm.settings

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

        SectionTitle("Scanning")
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Auto-crop new images", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Detect and crop to the document edges automatically",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = s.autoCrop,
                onCheckedChange = { vm.updateSettings(s.copy(autoCrop = it)) }
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))
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
        SectionTitle("PDF export")
        Text(
            "Page format — Fit to image matches each page's own aspect ratio (no letterboxing).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(com.docuscan.app.data.PageFormat.entries) { f ->
                FilterChip(
                    selected = s.pageFormat == f.name,
                    onClick = { vm.updateSettings(s.copy(pageFormat = f.name)) },
                    label = { Text(f.label) }
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Quality preset — higher DPI = sharper text, larger file.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        com.docuscan.app.data.PdfQualityPreset.entries.forEach { q ->
            RadioRow(
                label = q.label,
                hint = "${q.targetDpi} dpi${if (q.forceGrayscale) ", grayscale" else ""}",
                selected = s.pdfQuality == q.name,
                onClick = { vm.updateSettings(s.copy(pdfQuality = q.name)) }
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
            Text("1.0.0", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            "DocuScan is a local-first document scanner: all scanning, filtering and export happens on your device. " +
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
