package com.docuscan.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.docuscan.app.DocViewModel
import com.docuscan.app.data.DocRecord
import com.docuscan.app.util.ShareUtil
import androidx.compose.ui.platform.LocalContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun DocumentsScreen(vm: DocViewModel, snackbar: SnackbarHostState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { vm.refreshDocs() }

    var viewing by remember { mutableStateOf<DocRecord?>(null) }
    val docs = vm.docs

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "Documents",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        if (docs.isEmpty()) {
            EmptyState(
                Icons.Default.DateRange,
                "No documents yet",
                "Scan or import your first document from the Home tab.",
                Modifier.fillMaxSize()
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(docs, key = { it.id }) { doc ->
                    DocCard(
                        doc = doc,
                        onClick = { viewing = doc },
                        onDelete = {
                            vm.deleteDoc(doc.id)
                            scope.launch { snackbar.showSnackbar("Document deleted") }
                        }
                    )
                }
            }
        }
    }

    viewing?.let { doc ->
        DocViewerDialog(
            doc = doc,
            onDismiss = { viewing = null },
            onShare = {
                when {
                    doc.pdfUri != null -> ShareUtil.shareUri(context, android.net.Uri.parse(doc.pdfUri), "application/pdf")
                    doc.jpgUris.isNotEmpty() -> ShareUtil.shareUri(context, android.net.Uri.parse(doc.jpgUris[0]), "image/jpeg")
                }
            },
            onDelete = {
                viewing = null
                vm.deleteDoc(doc.id)
                scope.launch { snackbar.showSnackbar("Document deleted") }
            },
            onOpenPdf = {
                doc.pdfUri?.let { ShareUtil.openUri(context, android.net.Uri.parse(it), "application/pdf") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocCard(doc: DocRecord, onClick: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val fmt = remember { SimpleDateFormat("MMM d, yyyy", Locale.US) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { menu = true }
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
        ) {
            if (doc.jpgUris.isNotEmpty()) {
                RemoteBitmap(doc.jpgUris.first(), Modifier.fillMaxSize(), maxDim = 600)
            } else {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Icon(
                        AppIcons.Gallery,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp).align(Alignment.Center),
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color.Black.copy(alpha = 0.55f)
                ) {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Options", tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Share") }, onClick = {
                        menu = false
                        when {
                            doc.pdfUri != null -> ShareUtil.shareUri(context, android.net.Uri.parse(doc.pdfUri), "application/pdf")
                            doc.jpgUris.isNotEmpty() -> ShareUtil.shareUri(context, android.net.Uri.parse(doc.jpgUris[0]), "image/jpeg")
                        }
                    })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = {
                        menu = false
                        onDelete()
                    })
                }
            }
        }
        Column(Modifier.padding(10.dp)) {
            Text(
                doc.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Text(
                "${fmt.format(Date(doc.timestamp))} • ${doc.pageCount} page${if (doc.pageCount > 1) "s" else ""} • ${formatLabel(doc.format)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DocViewerDialog(
    doc: DocRecord,
    onDismiss: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onOpenPdf: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                .padding(16.dp)
        ) {
            Text(
                doc.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "${doc.pageCount} page${if (doc.pageCount > 1) "s" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            if (doc.jpgUris.isNotEmpty()) {
                Column(
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 8.dp)
                ) {
                    doc.jpgUris.forEach { uri ->
                        RemoteBitmap(uri, Modifier.fillMaxWidth().padding(vertical = 4.dp), maxDim = 1400)
                    }
                }
            } else {
                Text(
                    "This document was exported as PDF only.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (doc.pdfUri != null) {
                    TextButton(onClick = onOpenPdf) { Text("Open PDF") }
                }
                TextButton(onClick = onShare) { Text("Share") }
                TextButton(onClick = onDelete) { Text("Delete") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    }
}

private fun formatLabel(format: String): String = when (format) {
    "pdf" -> "PDF"
    "jpg" -> "JPG"
    else -> "PDF + JPG"
}
