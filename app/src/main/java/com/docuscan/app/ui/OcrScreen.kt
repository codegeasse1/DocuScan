package com.docuscan.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.docuscan.app.DocViewModel
import com.docuscan.app.scan.Dictionary
import com.docuscan.app.scan.Word
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * OCR review screen (like MakeACopy's OCR review): every recognized word is
 * shown with its confidence, tap to fix, get dictionary suggestions, or re-run
 * OCR on that single word. OCR is opt-in - it only starts when the user opens
 * this screen. The edited text is embedded as a searchable layer in PDFs.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OcrScreen(vm: DocViewModel, pageId: Long, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val page = vm.pages.firstOrNull { it.id == pageId }
    if (page == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    var initialRunDone by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf<Int?>(null) }
    var langMenu by remember { mutableStateOf(false) }

    val words = page.ocrWords ?: emptyList()
    val fullText = page.ocrText ?: ""

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val code = withContext(Dispatchers.IO) { vm.importOcrLang(uri) }
                if (code != null) {
                    snackbar.showSnackbar("Imported '$code' — recognizing…")
                    vm.setOcrLang(code)
                } else {
                    snackbar.showSnackbar("Couldn't import that language pack")
                }
            }
        }
    }

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
                Text("OCR review", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Page ${vm.pages.indexOfFirst { it.id == pageId } + 1} of ${vm.pages.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                TextButton(onClick = { langMenu = true }) {
                    Text(vm.settings.ocrLang)
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = langMenu, onDismissRequest = { langMenu = false }) {
                    vm.ocrLangs.forEach { lang ->
                        DropdownMenuItem(
                            text = { Text(lang) },
                            onClick = {
                                langMenu = false
                                if (lang != vm.settings.ocrLang) {
                                    snackbar.showSnackbar("Recognizing with '$lang'…")
                                    vm.setOcrLang(lang)
                                }
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Import language pack…", color = MaterialTheme.colorScheme.primary) },
                        onClick = {
                            langMenu = false
                            importLauncher.launch(arrayOf("*/*"))
                        }
                    )
                }
            }
            if (vm.pages.size > 1) {
                TextButton(onClick = { vm.runOcrAll() }) { Text("OCR all") }
            }
        }

        if (page.ocrBusy) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(
                        "Recognizing text…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            }
        } else {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 4.dp)
            ) {
                if (words.isEmpty()) {
                    Text(
                        if (fullText.isBlank()) {
                            "Nothing recognized yet — tap Re-run to try again."
                        } else {
                            "Word data unavailable, but text was extracted:"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                    OutlinedTextField(
                        value = fullText,
                        onValueChange = { vm.setOcrText(pageId, it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp),
                        placeholder = { Text("Recognized text…") }
                    )
                } else {
                    val lines = words.groupBy { it.lineIndex }
                    lines.keys.sorted().forEach { line ->
                        val lineWords = lines[line] ?: return@forEach
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.padding(bottom = 7.dp)
                        ) {
                            lineWords.sortedBy { it.midX }.forEach { w ->
                                val (bg, fg) = confColors(w.confidence)
                                Surface(
                                    onClick = { editingIndex = words.indexOf(w) },
                                    shape = RoundedCornerShape(7.dp),
                                    color = bg
                                ) {
                                    Text(
                                        w.text,
                                        color = fg,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        "Tap a word to fix it • green = high confidence, red = low",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
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
                    cm.setPrimaryClip(ClipData.newPlainText("OCR", fullText))
                    scope.launch { snackbar.showSnackbar("Copied to clipboard") }
                }, enabled = fullText.isNotBlank()) { Text("Copy") }
                TextButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, fullText)
                    }
                    runCatching {
                        context.startActivity(Intent.createChooser(send, "Share OCR text"))
                    }
                }, enabled = fullText.isNotBlank()) { Text("Share") }
                TextButton(onClick = {
                    vm.runOcr(pageId, force = true)
                }) { Text("Re-run OCR") }
                TextButton(onClick = onBack, enabled = !page.ocrBusy) {
                    Text("Done", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    val editIdx = editingIndex
    if (editIdx != null) {
        WordEditDialog(
            pageId = pageId,
            wordIndex = editIdx,
            pageWords = words,
            onDismiss = { editingIndex = null },
            onReOcr = { vm.reOcrWord(pageId, editIdx) },
            onApply = { newText ->
                vm.setWordText(pageId, editIdx, newText)
                editingIndex = null
            }
        )
    }
}

@Composable
private fun confColors(conf: Float): Pair<Color, Color> = when {
    conf >= 75f -> Color(0x332E7D32) to Color(0xFF2E7D32)
    conf >= 50f -> Color(0x33F9A825) to Color(0xFF8A6D00)
    else -> Color(0x33C62828) to Color(0xFFC62828)
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun WordEditDialog(
    pageId: Long,
    wordIndex: Int,
    pageWords: List<Word>,
    onDismiss: () -> Unit,
    onReOcr: () -> Unit,
    onApply: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val word = pageWords.getOrNull(wordIndex) ?: return
    var text by remember(pageId, wordIndex, word.text) { mutableStateOf(word.text) }
    var reOcrBusy by remember(pageId, wordIndex) { mutableStateOf(false) }
    var suggestions by remember(pageId, wordIndex, word.text) { mutableStateOf<List<String>>(emptyList()) }
    val originalText = remember(pageId, wordIndex) { word.text }

    LaunchedEffect(wordIndex, word.text) {
        if (word.confidence < 80f) {
            suggestions = withContext(Dispatchers.IO) { Dictionary.suggest(context, word.text) }
        }
    }
    LaunchedEffect(pageWords, reOcrBusy) {
        if (reOcrBusy) {
            val cur = pageWords.getOrNull(wordIndex)?.text
            if (cur != originalText) reOcrBusy = false
        }
    }

    val (bg, fg) = confColors(word.confidence)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit word") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .width(10.dp)
                            .height(10.dp)
                            .padding(0.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(shape = RoundedCornerShape(3.dp), color = bg, modifier = Modifier.fillMaxSize()) {}
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${word.confidence.toInt()}% confidence",
                        style = MaterialTheme.typography.labelMedium,
                        color = fg
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false
                )
                if (suggestions.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Suggestions", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 6.dp)
                    ) {
                        suggestions.forEach { s ->
                            AssistChip(
                                onClick = { text = s },
                                label = { Text(s) }
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                if (reOcrBusy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Re-running OCR on this word…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = {
                    reOcrBusy = true
                    onReOcr()
                }, enabled = !reOcrBusy) { Text("Re-OCR word") }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = { onApply(text) }) { Text("Apply", fontWeight = FontWeight.Bold) }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
