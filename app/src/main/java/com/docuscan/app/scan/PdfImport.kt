package com.docuscan.app.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri

/** Renders the pages of an existing PDF into bitmaps for the editor. */
object PdfImport {

    fun renderAll(context: Context, uri: Uri): List<Bitmap>? {
        return try {
            val fd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
            val out = mutableListOf<Bitmap>()
            fd.use { f ->
                val renderer = PdfRenderer(f)
                val count = renderer.pageCount
                for (i in 0 until count) {
                    val page = renderer.openPage(i)
                    val maxPt = maxOf(page.width, page.height).toFloat()
                    val scale = (2200f / maxPt).coerceIn(0.1f, 8f)
                    val w = (page.width * scale).toInt().coerceAtLeast(1)
                    val h = (page.height * scale).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    val c = Canvas(bmp)
                    c.translate(0f, 0f)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    out.add(bmp)
                    page.close()
                }
                renderer.close()
            }
            out.ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }
}
