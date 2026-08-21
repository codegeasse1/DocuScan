package com.docuscan.app.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import java.io.File

object BitmapUtil {

    fun fitMax(src: Bitmap, maxDim: Int): Bitmap {
        val m = maxOf(src.width, src.height)
        if (m <= maxDim) return src
        val scale = maxDim.toFloat() / m
        return Bitmap.createScaledBitmap(src, (src.width * scale).toInt(), (src.height * scale).toInt(), true)
    }

    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return src
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    fun rotate90(src: Bitmap): Bitmap = rotate(src, 90)

    fun crop(src: Bitmap, r: Rect): Bitmap {
        val left = r.left.coerceIn(0, src.width - 1)
        val top = r.top.coerceIn(0, src.height - 1)
        val w = r.width().coerceAtMost(src.width - left)
        val h = r.height().coerceAtMost(src.height - top)
        if (w <= 0 || h <= 0) return src
        return Bitmap.createBitmap(src, left, top, w, h)
    }

    /** Perspective warp: map the four corners (TL,TR,BR,BL in bitmap coords) onto a target rectangle. */
    fun perspectiveWarp(src: Bitmap, quad: List<android.graphics.PointF>, outW: Int = src.width, outH: Int = src.height): Bitmap {
        val srcPts = floatArrayOf(
            quad[0].x, quad[0].y,
            quad[1].x, quad[1].y,
            quad[2].x, quad[2].y,
            quad[3].x, quad[3].y
        )
        val dstPts = floatArrayOf(
            0f, 0f,
            outW.toFloat(), 0f,
            outW.toFloat(), outH.toFloat(),
            0f, outH.toFloat()
        )
        val m = Matrix()
        if (!m.setPolyToPoly(srcPts, 0, dstPts, 0, 4)) {
            return crop(src, boundingRect(quad, src.width, src.height))
        }
        val out = Bitmap.createBitmap(outW.coerceAtLeast(1), outH.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        c.drawColor(android.graphics.Color.WHITE)
        c.drawBitmap(src, m, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG or android.graphics.Paint.ANTI_ALIAS_FLAG))
        return out
    }

    /** Downscale to fit within maxW x maxH (preserving aspect); no-op when already smaller. */
    fun downscaleToFit(src: Bitmap, maxW: Int, maxH: Int): Bitmap {
        if (src.width <= maxW && src.height <= maxH) return src
        val s = minOf(maxW.toFloat() / src.width, maxH.toFloat() / src.height)
        val w = (src.width * s).toInt().coerceAtLeast(1)
        val h = (src.height * s).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    /** Luminance-only copy. */
    fun toGrayscale(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        val p = android.graphics.Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(
            android.graphics.ColorMatrix(floatArrayOf(
                0.299f, 0.587f, 0.114f, 0f, 0f,
                0.299f, 0.587f, 0.114f, 0f, 0f,
                0.299f, 0.587f, 0.114f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
        ) }
        c.drawBitmap(src, 0f, 0f, p)
        return out
    }

    private fun boundingRect(quad: List<android.graphics.PointF>, w: Int, h: Int): Rect {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = 0f
        var b = 0f
        for (p in quad) {
            l = minOf(l, p.x); t = minOf(t, p.y); r = maxOf(r, p.x); b = maxOf(b, p.y)
        }
        val left = l.toInt().coerceIn(0, w - 1)
        val top = t.toInt().coerceIn(0, h - 1)
        return Rect(left, top, r.toInt().coerceIn(left + 1, w), b.toInt().coerceIn(top + 1, h))
    }

    /** Load a bitmap from any uri (content or file), honoring EXIF rotation, capped at maxDim. */
    fun loadFromUri(context: Context, uri: Uri, maxDim: Int = 2000): Bitmap? {
        val tmp = File(context.cacheDir, "pick_${System.currentTimeMillis()}.jpg")
        try {
            val ins = context.contentResolver.openInputStream(uri) ?: return null
            ins.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            val bmp = decodeFile(tmp.absolutePath, maxDim) ?: return null
            val orientation = try {
                val ei = ExifInterface(tmp.absolutePath)
                when (ei.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } catch (e: Exception) {
                0
            }
            tmp.delete()
            return if (orientation != 0) rotate(bmp, orientation) else bmp
        } catch (e: Exception) {
            tmp.delete()
            return null
        }
    }

    fun decodeFile(path: String, maxDim: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        var sample = 1
        while (opts.outWidth / sample > maxDim || opts.outHeight / sample > maxDim) sample *= 2
        val o2 = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(path, o2)
    }
}
