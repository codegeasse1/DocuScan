package com.docuscan.app.scan

import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.graphics.RectF
import java.io.OutputStream

object PdfExporter {

    private const val A4_W = 595
    private const val A4_H = 842

    /** Renders every page onto its own A4 page (portrait or landscape), centered. */
    fun createPdf(pages: List<Bitmap>, out: OutputStream) {
        val doc = PdfDocument()
        for (bmp in pages) {
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
            doc.finishPage(page)
        }
        doc.writeTo(out)
        doc.close()
    }
}
