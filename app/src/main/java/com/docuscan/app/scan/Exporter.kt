package com.docuscan.app.scan

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.docuscan.app.DocViewModel
import com.docuscan.app.data.PageFormat
import com.docuscan.app.data.PdfQualityPreset
import java.io.ByteArrayInputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Exporter {

    data class Result(
        val pdfUri: Uri?,
        val jpgUris: List<Uri>,
        val shareFile: File?
    )

    /**
     * Applies a page's filter + adjustments, then (when the Auto-enhance setting is on)
     * the cheap local levels stretch, so what gets saved matches the editor preview.
     */
    private fun renderPage(vm: DocViewModel, index: Int, autoEnhance: Boolean): Bitmap {
        val page = vm.pages[index]
        val base = applyFilter(page.bitmap, page.filterId, page.brightness, page.contrast)
        return if (autoEnhance) AutoEnhance.apply(base) else base
    }

    fun run(context: Context, vm: DocViewModel, format: String): Result {
        val settings = vm.settings
        val autoEnhance = settings.autoEnhance
        val pageCount = vm.pages.size
        val pageFormat = PageFormat.fromName(settings.pageFormat, PageFormat.FIT_TO_IMAGE)
        val quality = PdfQualityPreset.fromName(settings.pdfQuality, PdfQualityPreset.HIGH)
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val base = "DocuScan_$ts"

        var pdfUri: Uri? = null
        var shareFile: File? = null
        val jpgUris = mutableListOf<Uri>()

        if ((format == "both" || format == "pdf") && pageCount > 0) {
            val f = File(context.cacheDir, "$base.pdf")
            f.outputStream().use { out ->
                // Rendered one page at a time so the whole document is never
                // materialised in memory at once.
                PdfExporter.createPdf(pageCount, { i -> renderPage(vm, i, autoEnhance) }, out, pageFormat, quality)
            }
            shareFile = f
            pdfUri = MediaSaver.savePdf(context, f, "$base.pdf")
        }
        if ((format == "both" || format == "jpg") && pageCount > 0) {
            for (i in 0 until pageCount) {
                var bmp = renderPage(vm, i, autoEnhance)
                if (!settings.jpegColor) {
                    val bw = applyFilter(bmp, "bw", 0f, 1f)
                    if (bw !== bmp) bmp = bw
                }
                val name = if (pageCount == 1) "$base.jpg" else "${base}_p${i + 1}.jpg"
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
                        ByteArrayInputStream(bytes)
                    }
                }
            }
        }

        vm.addHistory("$base", pageCount, format, pdfUri, jpgUris)
        return Result(pdfUri, jpgUris, shareFile)
    }

    /** Creates a shareable PDF for the current pages (cache dir), null on failure. */
    fun makePdf(context: Context, vm: DocViewModel): File? {
        return try {
            val settings = vm.settings
            val autoEnhance = settings.autoEnhance
            val f = File(context.cacheDir, "share_${System.currentTimeMillis()}.pdf")
            f.outputStream().use { out ->
                PdfExporter.createPdf(
                    vm.pages.size,
                    { i -> renderPage(vm, i, autoEnhance) },
                    out,
                    PageFormat.fromName(settings.pageFormat, PageFormat.FIT_TO_IMAGE),
                    PdfQualityPreset.fromName(settings.pdfQuality, PdfQualityPreset.HIGH)
                )
            }
            f
        } catch (e: Exception) {
            null
        }
    }
}
