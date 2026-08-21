package com.docuscan.app.scan

import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.graphics.RectF
import com.docuscan.app.data.PageFormat
import com.docuscan.app.data.PdfQualityPreset
import java.io.OutputStream
import kotlin.math.min

object PdfExporter {

    /**
     * Renders every page onto its own PDF page. The page size follows [pageFormat]
     * (FIT_TO_IMAGE matches the image's aspect ratio — no letterboxing; fixed formats
     * scale the image to fit the chosen paper). The bitmap is downscaled to [quality]'s
     * target DPI and optionally converted to grayscale, mirroring makeacopy's presets.
     */
    fun createPdf(
        pages: List<Bitmap>,
        out: OutputStream,
        pageFormat: PageFormat = PageFormat.FIT_TO_IMAGE,
        quality: PdfQualityPreset = PdfQualityPreset.STANDARD
    ) {
        val doc = PdfDocument()
        for (idx in pages.indices) {
            var bmp = pages[idx]

            // Scale to the target DPI (dominant file-size driver, like makeacopy's presets).
            if (pageFormat.isFixed) {
                val (maxW, maxH) = pageFormat.pixelsForDpi(quality.targetDpi)!!
                bmp = BitmapUtil.downscaleToFit(bmp, maxW, maxH)
            } else {
                val longEdgePx = (842f / 72f * quality.targetDpi).toInt()
                bmp = BitmapUtil.downscaleToFit(bmp, longEdgePx, longEdgePx)
            }

            if (quality.forceGrayscale) {
                bmp = BitmapUtil.toGrayscale(bmp)
            }

            val (pwRaw, phRaw) = pageFormat.pageSizePts(bmp.width, bmp.height)
            val landscape = bmp.width > bmp.height
            val pw = if (pageFormat.isFixed && landscape) phRaw else pwRaw
            val ph = if (pageFormat.isFixed && landscape) pwRaw else phRaw

            val page = doc.startPage(PdfDocument.PageInfo.Builder(pw.toInt(), ph.toInt(), 1).create())
            val scale = min(pw / bmp.width.toFloat(), ph / bmp.height.toFloat()) *
                if (pageFormat.isFixed) 0.95f else 1f
            val dw = bmp.width * scale
            val dh = bmp.height * scale
            val dx = (pw - dw) / 2f
            val dy = (ph - dh) / 2f
            page.canvas.drawBitmap(bmp, null, RectF(dx, dy, dx + dw, dy + dh), null)

            doc.finishPage(page)
        }
        doc.writeTo(out)
        doc.close()
    }
}
