package com.docuscan.app.scan

import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.graphics.RectF
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
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
     * When [texts] contains OCR text for a page, it is drawn as a near-invisible text
     * layer so the exported PDF is searchable.
     */
    fun createPdf(
        pages: List<Bitmap>,
        out: OutputStream,
        texts: List<String?>? = null,
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

            val text = texts?.getOrNull(idx)
            if (!text.isNullOrBlank()) {
                val tp = TextPaint().apply {
                    color = android.graphics.Color.argb(1, 0, 0, 0)
                    textSize = 10f
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                }
                val margin = 24f
                val layout = StaticLayout.Builder
                    .obtain(text, 0, text.length, tp, ((pw - 2 * margin)).toInt())
                    .setLineSpacing(0f, 1.25f)
                    .build()
                page.canvas.save()
                page.canvas.translate(dx + margin, dy + margin)
                layout.draw(page.canvas)
                page.canvas.restore()
            }

            doc.finishPage(page)
        }
        doc.writeTo(out)
        doc.close()
    }
}
