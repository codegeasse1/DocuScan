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
        val shareFile: File?
    )

    fun run(context: Context, vm: DocViewModel, format: String): Result {
        val filteredPages = vm.pages.map {
            applyFilter(it.bitmap, it.filterId, it.brightness, it.contrast)
        }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val base = "DocuScan_$ts"

        var pdfUri: Uri? = null
        var shareFile: File? = null
        val jpgUris = mutableListOf<Uri>()

        if (format == "both" || format == "pdf") {
            val f = File(context.cacheDir, "$base.pdf")
            f.outputStream().use { PdfExporter.createPdf(filteredPages, it) }
            shareFile = f
            pdfUri = MediaSaver.savePdf(context, f, "$base.pdf")
        }
        if (format == "both" || format == "jpg") {
            filteredPages.forEachIndexed { i, bmp ->
                val name = if (filteredPages.size == 1) "$base.jpg" else "${base}_p${i + 1}.jpg"
                MediaSaver.saveJpg(context, bmp, name)?.let { jpgUris.add(it) }
            }
        }

        vm.addHistory("$base", filteredPages.size, format, pdfUri, jpgUris)
        return Result(pdfUri, jpgUris, shareFile)
    }

    /** Creates a shareable PDF for the current pages (cache dir), null on failure. */
    fun makePdf(context: Context, vm: DocViewModel): File? {
        return try {
            val filteredPages = vm.pages.map {
                applyFilter(it.bitmap, it.filterId, it.brightness, it.contrast)
            }
            val f = File(context.cacheDir, "share_${System.currentTimeMillis()}.pdf")
            f.outputStream().use { PdfExporter.createPdf(filteredPages, it) }
            f
        } catch (e: Exception) {
            null
        }
    }
}
