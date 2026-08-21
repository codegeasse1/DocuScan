package com.docuscan.app.scan

import android.content.Context
import android.net.Uri
import com.docuscan.app.DocViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Exporter {

    data class Result(
        val pdfUri: Uri?,
        val jpgUris: List<Uri>,
        val shareFile: File?,
        val txtUri: Uri? = null
    )

    fun run(context: Context, vm: DocViewModel, format: String): Result {
        val settings = vm.settings
        val filteredPages = vm.pages.map {
            applyFilter(it.bitmap, it.filterId, it.brightness, it.contrast)
        }
        val ocrTexts = vm.pages.map { it.ocrText }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val base = "DocuScan_$ts"

        var pdfUri: Uri? = null
        var shareFile: File? = null
        var txtUri: Uri? = null
        val jpgUris = mutableListOf<Uri>()

        if (format == "txt") {
            val combined = ocrTexts.filterNotNull().filter { it.isNotBlank() }
            if (combined.isNotEmpty()) {
                val text = combined.joinToString("\n\n")
                val f = File(context.cacheDir, "$base.txt")
                f.writeText(text)
                shareFile = f
                txtUri = MediaSaver.saveTxt(context, text, "$base.txt")
            }
            vm.addHistory("$base", filteredPages.size, format, null, emptyList(), combined.joinToString("\n"))
            return Result(null, emptyList(), shareFile, txtUri)
        }

        if (format == "both" || format == "pdf") {
            val f = File(context.cacheDir, "$base.pdf")
            f.outputStream().use { PdfExporter.createPdf(filteredPages, it, ocrTexts) }
            shareFile = f
            pdfUri = MediaSaver.savePdf(context, f, "$base.pdf")
        }
        if (format == "both" || format == "jpg") {
            val jpgPages = if (settings.jpegColor) {
                filteredPages
            } else {
                filteredPages.map { applyFilter(it, "bw", 0f, 1f) }
            }
            jpgPages.forEachIndexed { i, bmp ->
                val name = if (jpgPages.size == 1) "$base.jpg" else "${base}_p${i + 1}.jpg"
                MediaSaver.saveJpg(context, bmp, name, settings.jpegQuality)?.let { jpgUris.add(it) }
            }
        }

        // Inbox Mode: mirror the export into the user's chosen folder.
        if (settings.inboxEnabled && settings.inboxUri.isNotBlank()) {
            val tree = Uri.parse(settings.inboxUri)
            pdfUri?.let { pdf ->
                val uriOfFile = MediaSaver.saveToInbox(context, tree, "$base.pdf") {
                    context.contentResolver.openInputStream(pdf)
                }
                if (uriOfFile != null) pdfUri = uriOfFile
            }
            jpgUris.toList().forEach { jpg ->
                // Keep the media-store copies (they're referenced by history); just mirror to inbox.
                runCatching {
                    val bytes = context.contentResolver.openInputStream(jpg)?.readBytes() ?: return@forEach
                    MediaSaver.saveToInbox(context, tree, File(jpg.lastPathSegment ?: "$base.jpg").name) {
                        java.io.ByteArrayInputStream(bytes)
                    }
                }
            }
        }

        val searchText = ocrTexts.filterNotNull().filter { it.isNotBlank() }.joinToString("\n")
        vm.addHistory("$base", filteredPages.size, format, pdfUri, jpgUris, searchText)
        return Result(pdfUri, jpgUris, shareFile)
    }

    /** Creates a shareable PDF for the current pages (cache dir), null on failure. */
    fun makePdf(context: Context, vm: DocViewModel): File? {
        return try {
            val filteredPages = vm.pages.map {
                applyFilter(it.bitmap, it.filterId, it.brightness, it.contrast)
            }
            val ocrTexts = vm.pages.map { it.ocrText }
            val f = File(context.cacheDir, "share_${System.currentTimeMillis()}.pdf")
            f.outputStream().use { PdfExporter.createPdf(filteredPages, it, ocrTexts) }
            f
        } catch (e: Exception) {
            null
        }
    }
}
