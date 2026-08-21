package com.docuscan.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.docuscan.app.DocViewModel
import com.docuscan.app.scan.AutoCrop
import com.docuscan.app.scan.BitmapUtil
import com.docuscan.app.scan.Exporter
import com.docuscan.app.scan.FILTERS
import com.docuscan.app.scan.applyFilter
import com.docuscan.app.util.ShareUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun EditorScreen(vm: DocViewModel, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val page = vm.pages.getOrNull(vm.selectedPage)

    var cropMode by remember { mutableStateOf(false) }
    var ocrMode by remember { mutableStateOf(false) }
    var showAdjust by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var saveMenu by remember { mutableStateOf(false) }
    var jpgOptions by remember { mutableStateOf(false) }
    var addDialog by remember { mutableStateOf(false) }
    var discardDialog by remember { mutableStateOf(false) }
    var autoCropping by remember { mutableStateOf(false) }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { BitmapUtil.loadFromUri(context, uri, 2200) }
                if (bmp != null) vm.addBitmap(bmp)
                else snackbar.showSnackbar("Couldn't load that image")
            }
        }
    }

    if (page == null) {
        LaunchedEffect(Unit) { vm.selectTab(com.docuscan.app.Tab.Home) }
        return
    }

    var filtered by remember(page.id, page.filterId, page.brightness, page.contrast) {
        mutableStateOf<Bitmap?>(null)
    }
    LaunchedEffect(page.id, page.filterId, page.brightness, page.contrast) {
        // Filters (esp. the OpenCV cleanup presets) run off the main thread so the UI stays smooth.
        filtered = null
        filtered = withContext(Dispatchers.Default) {
            applyFilter(page.bitmap, page.filterId, page.brightness, page.contrast)
        }
    }

    fun doExport(format: String) {
        exporting = true
        scope.launch {
            val res = withContext(Dispatchers.IO) { Exporter.run(context, vm, format) }
            exporting = false
            val where = when (format) {
                "jpg" -> "Pictures/DocuScan"
                "txt" -> "Download/DocuScan"
                else -> "Download/DocuScan"
            }
            val what = when (format) {
                "both" -> "PDF + JPGs"
                "pdf" -> "PDF"
                "jpg" -> "JPGs"
                else -> "Text"
            }
            val inbox = if (vm.settings.inboxEnabled) " + inbox" else ""
            snackbar.showSnackbar("Saved $what to $where$inbox")
            vm.newDoc()
            vm.selectTab(com.docuscan.app.Tab.Documents)
        }
    }

    fun sharePdf() {
        scope.launch {
            val f = withContext(Dispatchers.IO) { Exporter.makePdf(context, vm) }
            if (f != null) ShareUtil.shareFile(context, f, "application/pdf")
        }
    }

    fun autoCrop() {
        if (autoCropping) return
        autoCropping = true
        scope.launch {
            val pts = withContext(Dispatchers.IO) { AutoCrop.detectCorners(page.bitmap) }
            autoCropping = false
            if (pts != null) {
                vm.replaceSelected(BitmapUtil.perspectiveWarp(page.bitmap, pts))
                snackbar.showSnackbar("Auto-cropped to the detected page")
            } else {
                snackbar.showSnackbar("Couldn't find a page — use Crop")
            }
        }
    }

    if (cropMode) {
        CropOverlay(
            bitmap = page.bitmap,
            onApply = { bmp ->
                vm.replaceSelected(bmp)
                cropMode = false
            },
            onCancel = { cropMode = false }
        )
        return
    }

    if (ocrMode) {
        OcrScreen(vm, page.id, snackbar, onBack = { ocrMode = false })
        return
    }

    if (addDialog) {
        AlertDialog(
            onDismissRequest = { addDialog = false },
            title = { Text("Add a page") },
            text = {
                Column {
                    Text("Scan another page with the camera, or import it from your gallery.")
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { addDialog = false; vm.openCamera(fromEditor = true) }) {
                        Icon(AppIcons.Camera, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Camera")
                    }
                    TextButton(onClick = { addDialog = false; galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Icon(AppIcons.Gallery, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Gallery")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { addDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (discardDialog) {
        AlertDialog(
            onDismissRequest = { discardDialog = false },
            title = { Text("Discard this scan?") },
            text = { Text("Your current pages will be lost.") },
            confirmButton = {
                TextButton(onClick = {
                    discardDialog = false
                    vm.newDoc()
                    vm.selectTab(com.docuscan.app.Tab.Home)
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { discardDialog = false }) { Text("Keep editing") }
            }
        )
    }

    if (jpgOptions) {
        JpgOptionsDialog(
            vm = vm,
            onExport = { jpgOptions = false; doExport("jpg") },
            onDismiss = { jpgOptions = false }
        )
    }

    if (autoCropping) {
        LoadingOverlay("Detecting page corners…")
    }

    if (exporting) {
        LoadingOverlay("Exporting…")
    }

    Column(Modifier.fillMaxSize()) {
        // ===== Top bar =====
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { if (vm.pages.isNotEmpty()) discardDialog = true else vm.selectTab(com.docuscan.app.Tab.Home) }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    "Page ${vm.selectedPage + 1} of ${vm.pages.size}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "DocuScan",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { sharePdf() }) {
                Icon(Icons.Default.Share, contentDescription = "Share")
            }
            Box {
                IconButton(onClick = { saveMenu = true }) {
                    Icon(Icons.Default.CheckCircle, contentDescription = "Save")
                }
                DropdownMenu(expanded = saveMenu, onDismissRequest = { saveMenu = false }) {
                    DropdownMenuItem(text = { Text("Save PDF + JPG") }, onClick = { saveMenu = false; doExport("both") })
                    DropdownMenuItem(text = { Text("Save as PDF") }, onClick = { saveMenu = false; doExport("pdf") })
                    DropdownMenuItem(text = { Text("Save as JPG") }, onClick = { saveMenu = false; doExport("jpg") })
                    DropdownMenuItem(
                        text = { Text("Save JPG (options…)") },
                        onClick = { saveMenu = false; jpgOptions = true }
                    )
                    DropdownMenuItem(
                        text = { Text("Save as text (TXT)") },
                        enabled = vm.pages.any { !it.ocrText.isNullOrBlank() },
                        onClick = { saveMenu = false; doExport("txt") }
                    )
                }
            }
        }

        // ===== Image area =====
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            val f = filtered
            if (f != null) {
                Image(
                    bitmap = f.asImageBitmap(),
                    contentDescription = "Scanned page",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    contentScale = ContentScale.Fit
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            // Action pill overlay
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.9f),
                contentColor = MaterialTheme.colorScheme.inverseOnSurface
            ) {
                Row(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                    ActionButton("OCR", AppIcons.Ocr) { ocrMode = true }
                    ActionButton("AutoCrop", AppIcons.Crop) { autoCrop() }
                    ActionButton("Crop", AppIcons.Crop) { cropMode = true }
                    ActionButton("Rotate", Icons.Default.Refresh) { vm.rotateSelected() }
                    ActionButton("Delete", Icons.Default.Delete) { vm.removePage(vm.selectedPage) }
                }
            }
        }

        // ===== Adjust panel =====
        if (showAdjust) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Brightness", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { vm.resetAdjustments() }) { Text("Reset") }
                }
                Slider(
                    value = page.brightness,
                    onValueChange = { vm.setBrightness(it) },
                    valueRange = -1f..1f
                )
                Row {
                    Text("Contrast", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.width(8.dp))
                }
                Slider(
                    value = page.contrast,
                    onValueChange = { vm.setContrast(it) },
                    valueRange = 0.5f..1.6f
                )
            }
        }

        // ===== Filters =====
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(FILTERS) { _, f ->
                FilterChip(
                    selected = page.filterId == f.id,
                    onClick = { vm.setFilter(f.id) },
                    label = { Text(f.label) }
                )
            }
            item {
                FilterChip(
                    selected = showAdjust,
                    onClick = { showAdjust = !showAdjust },
                    label = { Text("Adjust") },
                    leadingIcon = {
                        Icon(
                            AppIcons.Tune,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }
        }

        // ===== Pages strip =====
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Pages", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { vm.movePage(vm.selectedPage, -1) }, enabled = vm.selectedPage > 0) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move left")
            }
            IconButton(onClick = { vm.movePage(vm.selectedPage, 1) }, enabled = vm.selectedPage < vm.pages.size - 1) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move right")
            }
            IconButton(onClick = { vm.removePage(vm.selectedPage) }, enabled = vm.pages.size > 1) {
                Icon(Icons.Default.Delete, contentDescription = "Delete page", tint = MaterialTheme.colorScheme.error)
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                AddPageTile { addDialog = true }
            }
            itemsIndexed(vm.pages, key = { _, p -> p.id }) { index, p ->
                val selected = index == vm.selectedPage
                Box(
                    Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .androidClickable { vm.selectPage(index) }
                ) {
                    Image(
                        bitmap = p.bitmap.asImageBitmap(),
                        contentDescription = "Page ${index + 1}",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                    Text(
                        "${index + 1}",
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(topStart = 8.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun ActionButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        contentColor = androidx.compose.material3.LocalContentColor.current
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(5.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun AddPageTile(onClick: () -> Unit) {
    Box(
        Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .androidClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("Add", style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun Modifier.androidClickable(onClick: () -> Unit): Modifier = this.then(
    clickable(onClick = onClick)
)

@Composable
private fun JpgOptionsDialog(vm: DocViewModel, onExport: () -> Unit, onDismiss: () -> Unit) {
    val s = vm.settings
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("JPG export options") },
        text = {
            Column {
                Text("Quality", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = s.jpegQuality.toFloat(),
                    onValueChange = { vm.updateSettings(s.copy(jpegQuality = it.toInt())) },
                    valueRange = 50f..100f
                )
                Text(
                    "${s.jpegQuality}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                Row {
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
            }
        },
        confirmButton = { TextButton(onClick = onExport) { Text("Export") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
