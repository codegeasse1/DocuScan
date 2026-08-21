package com.docuscan.app.scan

import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.graphics.RectF
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import java.io.OutputStream

object PdfExporter {

    private const val A4_W = 595
    private const val A4_H = 842

    /**
     * Renders every page onto its own A4 page (portrait or landscape), centered.
     * When [texts] contains OCR text for a page, it is drawn as a near-invisible
     * text layer so the exported PDF is searchable.
     */
    fun createPdf(pages: List<Bitmap>, out: OutputStream, texts: List<String?>? = null) {
        val doc = PdfDocument()
        for (idx in pages.indices) {
            val bmp = pages[idx]
            val landscape = bmp.width > bmp.height
            val pw = if (landscape) A4_H else A4_W
            val ph = if (landscape) A4_W else A4_H
            val page = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, 1).create())
            val scale = Math.min(pw / bmp.width.toFloat(), ph / bmp.height.toFloat()) * 0.95f
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
