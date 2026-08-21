package com.docuscan.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.docuscan.app.DocViewModel

/**
 * OCR review screen. OCR is opt-in: it only starts when the user opens this
 * screen (or taps Re-run). The recognized text is editable; the saved text is
 * embedded as a searchable layer in exported PDFs.
 */
@Composable
fun OcrScreen(vm: DocViewModel, pageId: Long, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val context = LocalContext.current
    val page = vm.pages.firstOrNull { it.id == pageId }
    if (page == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    var text by remember(pageId, page.ocrText, page.ocrBusy) { mutableStateOf(page.ocrText ?: "") }
    var initialRunDone by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (!initialRunDone && page.ocrText.isNullOrBlank() && !page.ocrBusy) {
            initialRunDone = true
            vm.runOcr(pageId)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f)) {
                Text("OCR text", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Page ${vm.pages.indexOfFirst { it.id == pageId } + 1} of ${vm.pages.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (vm.pages.size > 1) {
                TextButton(onClick = { vm.runOcrAll() }) { Text("OCR all pages") }
            }
        }

        if (page.ocrBusy) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Recognizing text…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    placeholder = { Text("Recognized text will appear here…") }
                )
                Spacer(Modifier.width(1.dp))
                val words = text.trim().split(Regex("\\s+")).count { it.isNotBlank() }
                Text(
                    if (text.isBlank()) {
                        "No text yet — tap Re-run OCR. OCR runs on-device and fully offline."
                    } else {
                        "$words words — this text is embedded as a searchable layer in exported PDFs."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = {
                    val cm = context.getSystemService(ClipboardManager::class.java)
                    cm.setPrimaryClip(ClipData.newPlainText("OCR", text))
                    snackbar.showSnackbar("Copied to clipboard")
                }) { Text("Copy") }
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    runCatching {
                        context.startActivity(Intent.createChooser(send, "Share OCR text"))
                    }
                }) { Text("Share") }
                TextButton(onClick = { vm.runOcr(pageId, force = true) }) { Text("Re-run OCR") }
                TextButton(onClick = {
                    vm.setOcrText(pageId, text)
                    onBack()
                }) { Text("Save", fontWeight = FontWeight.Bold) }
            }
        }
    }
}
