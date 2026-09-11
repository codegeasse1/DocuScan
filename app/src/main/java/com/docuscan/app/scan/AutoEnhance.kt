package com.docuscan.app.scan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import kotlin.math.sqrt

/**
 * Fast, automatic document enhancement used by the "Auto-enhance" setting.
 *
 * Unlike the OpenCV *Enhanced* preset (several full-res Mat passes), this samples a grid
 * of pixels to build a luminance histogram, derives robust black/white points (tenth
 * percentiles) and applies the resulting levels stretch in a single hardware-accelerated
 * ColorMatrix blit. That makes it cheap enough to run for every page on a phone without
 * any noticeable CPU / battery / memory pressure - the work is a fraction of a normal
 * preview draw, and only ever runs when a page's pixels or adjustments change.
 */
object AutoEnhance {

    private const val TARGET_SAMPLES = 20_000
    /** Below this luminance range the page is effectively flat - leave it alone. */
    private const val MIN_RANGE = 14f

    /** Returns an enhanced copy of [src] (ARGB_8888), or [src] when no change is needed. */
    fun apply(src: Bitmap): Bitmap {
        if (src.width < 2 || src.height < 2) return src
        val histogram = sampleHistogram(src)
        var total = 0
        for (v in histogram) total += v
        if (total <= 0) return src

        val lowCut = (total * 0.005f).toInt()
        val highCut = (total * 0.005f).toInt()

        var acc = 0
        var lo = 0
        for (i in 0 until 256) {
            acc += histogram[i]
            if (acc > lowCut) { lo = i; break }
        }
        acc = 0
        var hi = 255
        for (i in 255 downTo 0) {
            acc += histogram[i]
            if (acc > highCut) { hi = i; break }
        }

        val range = (hi - lo).toFloat()
        if (range < MIN_RANGE) return src

        // Gentle levels stretch: map [lo, hi] onto [0, 255] with a small contrast bump
        // and a tiny lift so paper reads clean white without clipping the darks.
        val scale = 255f / range
        val k = 1.05f
        val a = scale * k
        val off = 2f - lo.toFloat() * a

        val matrix = ColorMatrix(
            floatArrayOf(
                a, 0f, 0f, 0f, off,
                0f, a, 0f, 0f, off,
                0f, 0f, a, 0f, off,
                0f, 0f, 0f, 1f, 0f
            )
        )

        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    /** Cheap luminance histogram built from a strided sample grid (never touches every pixel). */
    private fun sampleHistogram(src: Bitmap): IntArray {
        val histogram = IntArray(256)
        val w = src.width
        val h = src.height
        val step = maxOf(1, sqrt((w.toDouble() * h) / TARGET_SAMPLES).toInt())
        val row = IntArray(w)
        var y = 0
        while (y < h) {
            src.getPixels(row, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) {
                val c = row[x]
                val lum = (((c shr 16) and 255) * 77 + ((c shr 8) and 255) * 151 + (c and 255) * 28) shr 8
                histogram[lum]++
                x += step
            }
            y += step
        }
        return histogram
    }
}
