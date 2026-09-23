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
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import com.docuscan.app.scan.AutoEnhance
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
    var showAdjust by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var saveMenu by remember { mutableStateOf(false) }
    var jpgOptions by remember { mutableStateOf(false) }
    var addDialog by remember { mutableStateOf(false) }
    var discardDialog by remember { mutableStateOf(false) }
    // When on, filter / brightness / contrast changes are applied to every page (batch mode).
    var applyToAll by remember { mutableStateOf(false) }
    var rememberCameraForSession by remember(vm.cameraPreferredForSession) {
        mutableStateOf(vm.cameraPreferredForSession)
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val bitmaps = withContext(Dispatchers.IO) {
                    uris.mapNotNull { BitmapUtil.loadFromUri(context, it, DocViewModel.MAX_IMPORT_DIM) }
                }
                if (bitmaps.isNotEmpty()) vm.addBitmaps(bitmaps)
                else snackbar.showSnackbar("Couldn't load those images")
            }
        }
    }

    LaunchedEffect(vm.screen, vm.autoCropNextPage, page?.id) {
        if (vm.screen == com.docuscan.app.Screen.Editor && vm.autoCropNextPage && page != null) {
            cropMode = true
            vm.consumeAutoCropNextPage()
        }
    }

    if (page == null) {
        LaunchedEffect(Unit) { vm.selectTab(com.docuscan.app.Tab.Home) }
        return
    }

    // Keyed on the source bitmap too, so rotating/cropping (which replace the bitmap)
    // refreshes the preview instead of leaving the previous pixels on screen.
    val srcBitmap = page.bitmap
    val autoEnhance = vm.settings.autoEnhance
    var filtered by remember(srcBitmap, page.filterId, page.brightness, page.contrast, autoEnhance) {
        mutableStateOf<Bitmap?>(null)
    }
    LaunchedEffect(srcBitmap, page.filterId, page.brightness, page.contrast, autoEnhance) {
        // Filters (esp. the OpenCV cleanup presets) and the auto-enhancer run off the
        // main thread so the UI stays smooth.
        filtered = withContext(Dispatchers.Default) {
            val base = applyFilter(srcBitmap, page.filterId, page.brightness, page.contrast)
            if (autoEnhance) AutoEnhance.apply(base) else base
        }
    }

    fun doExport(format: String) {
        exporting = true
        scope.launch {
            val res = withContext(Dispatchers.IO) { Exporter.run(context, vm, format) }
            exporting = false
            val where = when (format) {
                "jpg" -> "Pictures/DocuScan"
                else -> "Download/DocuScan"
            }
            val what = when (format) {
                "both" -> "PDF + JPGs"
                "pdf" -> "PDF"
                "jpg" -> "JPGs"
                else -> "Document"
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

    if (addDialog) {
        AlertDialog(
            onDismissRequest = { addDialog = false },
            title = { Text("Add a page") },
            text = {
                Column {
                    Text("Scan another page with the camera, or import it from your gallery.")
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Use camera automatically for this session",
                            Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Switch(
                            checked = rememberCameraForSession,
                            onCheckedChange = { rememberCameraForSession = it }
                        )
                    }
                    Text(
                        "Until you close DocuScan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        addDialog = false
                        vm.updateCameraPreferredForSession(rememberCameraForSession)
                        vm.openCamera(fromEditor = true, autoCrop = true)
                    }) {
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
                }
            }
        }

        // ===== Image area (no controls overlap the document) =====
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            // Show the previous/raw bitmap while a filter recomputes so the preview never
            // flashes empty - rotation and cropping appear instantly.
            val f = filtered
            Image(
                bitmap = (f ?: srcBitmap).asImageBitmap(),
                contentDescription = "Scanned page",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                contentScale = ContentScale.Fit
            )
            if (f == null) {
                LinearProgressIndicator(
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                )
            }
        }

        // ===== Bottom control deck (transparent glass) =====
        GlassPanel(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ActionButton("Crop", AppIcons.Crop) { cropMode = true }
                ActionButton("Rotate", Icons.Default.Refresh) { vm.rotateSelected() }
                ActionButton("Delete", Icons.Default.Delete) { vm.removePage(vm.selectedPage) }
            }

            if (showAdjust) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Brightness", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    TextButton(onClick = { vm.resetAdjustments(applyToAll) }) { Text("Reset") }
                }
                Slider(
                    value = page.brightness,
                    onValueChange = { vm.setBrightness(it, applyToAll) },
                    valueRange = -1f..1f
                )
                Text("Contrast", style = MaterialTheme.typography.labelMedium)
                Slider(
                    value = page.contrast,
                    onValueChange = { vm.setContrast(it, applyToAll) },
                    valueRange = 0.5f..1.6f
                )
            }

            if (vm.pages.size > 1) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Apply filters & adjustments to all ${vm.pages.size} pages",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Switch(checked = applyToAll, onCheckedChange = { applyToAll = it })
                }
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
                    onClick = { vm.setFilter(f.id, applyToAll) },
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
            // Reorder the selected page within the document. These stay tappable even when
            // there is nothing to move, so a tap always gives feedback instead of silently
            // doing nothing.
            IconButton(
                onClick = {
                    if (vm.selectedPage > 0) {
                        vm.movePage(vm.selectedPage, -1)
                    } else {
                        scope.launch {
                            snackbar.showSnackbar(
                                if (vm.pages.size <= 1) "Add more pages to reorder them"
                                else "This is already the first page"
                            )
                        }
                    }
                }
            ) {
                Icon(Icons.Default.KeyboardArrowLeft, contentDescription = "Move page left")
            }
            IconButton(
                onClick = {
                    if (vm.selectedPage < vm.pages.size - 1) {
                        vm.movePage(vm.selectedPage, 1)
                    } else {
                        scope.launch {
                            snackbar.showSnackbar(
                                if (vm.pages.size <= 1) "Add more pages to reorder them"
                                else "This is already the last page"
                            )
                        }
                    }
                }
            ) {
                Icon(Icons.Default.KeyboardArrowRight, contentDescription = "Move page right")
            }
            IconButton(onClick = { vm.removePage(vm.selectedPage) }, enabled = vm.pages.isNotEmpty()) {
                Icon(Icons.Default.Delete, contentDescription = "Delete page", tint = MaterialTheme.colorScheme.error)
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                AddPageTile {
                    if (vm.cameraPreferredForSession) {
                        vm.openCamera(fromEditor = true, autoCrop = true)
                    } else {
                        addDialog = true
                    }
                }
            }
            itemsIndexed(vm.pages, key = { _, p -> p.id }) { index, p ->
                val selected = index == vm.selectedPage
                Box(
                    Modifier
                        .size(72.dp)
                        .animateItem()
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
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        contentColor = LocalContentColor.current
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
                    valueRange = 90f..100f
                )
                Text(
                    "${s.jpegQuality}% — 100% keeps maximum quality",
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
