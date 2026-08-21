package com.docuscan.app.scan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

data class FilterOption(val id: String, val label: String)

val FILTERS = listOf(
    FilterOption("original", "Original"),
    FilterOption("natural", "Natural"),
    FilterOption("enhanced", "Enhanced"),
    FilterOption("cleantext", "Clean Text"),
    FilterOption("magic", "Magic"),
    FilterOption("bw", "B&W"),
    FilterOption("gray", "Gray"),
    FilterOption("sepia", "Sepia"),
    FilterOption("polaroid", "Polaroid"),
    FilterOption("vintage", "Vintage"),
    FilterOption("soft", "Soft"),
    FilterOption("warm", "Warm"),
    FilterOption("cool", "Cool"),
    FilterOption("ocean", "Ocean"),
    FilterOption("rose", "Rose"),
    FilterOption("blue", "Blue"),
    FilterOption("invert", "Invert"),
    FilterOption("vivid", "Vivid"),
    FilterOption("faded", "Faded"),
    FilterOption("crisp", "Crisp"),
    FilterOption("sharpen", "Sharpen"),
    FilterOption("night", "Night"),
)

private fun contrastMatrix(k: Float): ColorMatrix {
    val off = 127.5f * (1 - k)
    return ColorMatrix(
        floatArrayOf(
            k, 0f, 0f, 0f, off,
            0f, k, 0f, 0f, off,
            0f, 0f, k, 0f, off,
            0f, 0f, 0f, 1f, 0f
        )
    )
}

private fun translateMatrix(t: Float): ColorMatrix {
    return ColorMatrix(
        floatArrayOf(
            1f, 0f, 0f, 0f, t,
            0f, 1f, 0f, 0f, t,
            0f, 0f, 1f, 0f, t,
            0f, 0f, 0f, 1f, 0f
        )
    )
}

private fun baseMatrix(id: String): ColorMatrix? = when (id) {
    "magic" -> ColorMatrix().apply {
        setSaturation(1.22f)
        postConcat(contrastMatrix(1.12f))
        postConcat(translateMatrix(4f))
    }
    "gray" -> ColorMatrix().apply { setSaturation(0f) }
    "bw" -> null // handled specially (Otsu binarization)
    "sepia" -> ColorMatrix(
        floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    )
    "polaroid" -> ColorMatrix().apply {
        setSaturation(0.82f)
        postConcat(contrastMatrix(0.94f))
        postConcat(translateMatrix(14f))
    }
    "vintage" -> ColorMatrix().apply {
        setSaturation(0.65f)
        postConcat(ColorMatrix(
            floatArrayOf(
                1.05f, 0.06f, 0f, 0f, 6f,
                0f, 0.95f, 0f, 0f, 0f,
                0f, 0f, 0.8f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        ))
        postConcat(contrastMatrix(0.9f))
        postConcat(translateMatrix(10f))
    }
    "soft" -> ColorMatrix().apply {
        setSaturation(1.05f)
        postConcat(contrastMatrix(0.82f))
        postConcat(translateMatrix(12f))
    }
    "warm" -> ColorMatrix(
        floatArrayOf(
            1.15f, 0f, 0f, 0f, 10f,
            0f, 1.05f, 0f, 0f, 0f,
            0f, 0f, 0.88f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    )
    "cool" -> ColorMatrix(
        floatArrayOf(
            0.88f, 0f, 0f, 0f, 0f,
            0f, 1.0f, 0f, 0f, 0f,
            0f, 0f, 1.15f, 0f, 12f,
            0f, 0f, 0f, 1f, 0f
        )
    )
    "ocean" -> ColorMatrix().apply {
        setSaturation(0.85f)
        postConcat(ColorMatrix(
            floatArrayOf(
                0.85f, 0.15f, 0f, 0f, 0f,
                0.05f, 0.95f, 0f, 0f, 4f,
                0.1f, 0.25f, 0.95f, 0f, 10f,
                0f, 0f, 0f, 1f, 0f
            )
        ))
    }
    "rose" -> ColorMatrix().apply {
        setSaturation(1.05f)
        postConcat(ColorMatrix(
            floatArrayOf(
                1.12f, 0.12f, 0f, 0f, 6f,
                0.04f, 0.92f, 0.1f, 0f, 0f,
                0.05f, 0.12f, 0.85f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            )
        ))
    }
    "blue" -> ColorMatrix().apply {
        setSaturation(0f)
        postConcat(ColorMatrix(
            floatArrayOf(
                0.15f, 0.15f, 0.15f, 0f, 0f,
                0.45f, 0.45f, 0.45f, 0f, 0f,
                0.95f, 0.95f, 0.95f, 0f, 12f,
                0f, 0f, 0f, 1f, 0f
            )
        ))
    }
    "invert" -> ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        )
    )
    "vivid" -> ColorMatrix().apply { setSaturation(1.6f) }
    "faded" -> ColorMatrix().apply {
        setSaturation(0.65f)
        postConcat(contrastMatrix(0.92f))
        postConcat(translateMatrix(18f))
    }
    "crisp" -> ColorMatrix().apply {
        setSaturation(1.12f)
        postConcat(contrastMatrix(1.28f))
    }
    "night" -> ColorMatrix().apply {
        setSaturation(0.9f)
        postConcat(contrastMatrix(1.15f))
        postConcat(translateMatrix(-34f))
    }
    else -> null
}

fun applyFilter(src: Bitmap, filterId: String, brightness: Float, contrast: Float): Bitmap {
    if (filterId == "bw") {
        val pre = applyMatrixToBitmap(src, baseMatrix("gray"), brightness, contrast)
        val b = binarize(pre)
        if (pre !== src) pre.recycle()
        return b
    }
    if (filterId == "sharpen") {
        val sharp = sharpen(src)
        val out = applyMatrixToBitmap(sharp, null, brightness, contrast)
        if (out !== sharp && out !== src) sharp.recycle()
        return out
    }
    if (filterId == "natural" || filterId == "enhanced" || filterId == "cleantext") {
        return Cleanup.apply(src, filterId)
    }
    return applyMatrixToBitmap(src, baseMatrix(filterId), brightness, contrast)
}

private fun applyMatrixToBitmap(src: Bitmap, base: ColorMatrix?, brightness: Float, contrast: Float): Bitmap {
    val needsWork = base != null || contrast != 1f || brightness != 0f
    if (!needsWork) return src
    val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val c = Canvas(out)
    val combined = ColorMatrix()
    if (base != null) combined.set(base)
    if (contrast != 1f) combined.postConcat(contrastMatrix(contrast))
    if (brightness != 0f) combined.postConcat(translateMatrix(brightness * 25f))
    val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(combined) }
    c.drawBitmap(src, 0f, 0f, paint)
    return out
}

private fun luminance(c: Int): Int =
    (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114 + 500) / 1000

/** Spatial sharpen: classic 3x3 kernel (0,-1,0 / -1,9,-1 / 0,-1,0), edges kept as-is. */
private fun sharpen(src: Bitmap): Bitmap {
    val w = src.width
    val h = src.height
    val px = IntArray(w * h)
    src.getPixels(px, 0, w, 0, 0, w, h)
    val out = px.copyOf()
    val kernel = intArrayOf(0, -1, 0, -1, 9, -1, 0, -1, 0)
    for (y in 1 until h - 1) {
        var i = y * w
        for (x in 1 until w - 1) {
            i++
            var rr = 0
            var gg = 0
            var bb = 0
            var ki = 0
            for (ky in -1..1) {
                var kx = -1
                while (kx <= 1) {
                    val k = kernel[ki++]
                    val c = px[(y + ky) * w + x + kx]
                    rr += ((c shr 16) and 255) * k
                    gg += ((c shr 8) and 255) * k
                    bb += (c and 255) * k
                    kx++
                }
            }
            out[i] = (0xFF000000.toInt()
                or ((rr.coerceIn(0, 255)) shl 16)
                or ((gg.coerceIn(0, 255)) shl 8)
                or (bb.coerceIn(0, 255)))
        }
    }
    val res = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    res.setPixels(out, 0, w, 0, 0, w, h)
    return res
}

/** Real black & white: Otsu's method picks the optimal threshold from the luminance histogram. */
private fun binarize(src: Bitmap): Bitmap {
    val w = src.width
    val h = src.height
    val px = IntArray(w * h)
    src.getPixels(px, 0, w, 0, 0, w, h)
    val hist = IntArray(256)
    for (c in px) hist[luminance(c)]++
    val total = w * h
    var sumAll = 0.0
    for (i in 0 until 256) sumAll += i * hist[i]
    var sumB = 0.0
    var wB = 0
    var maxVar = -1.0
    var th = 127
    for (t in 0 until 256) {
        wB += hist[t]
        if (wB == 0) continue
        val wF = total - wB
        if (wF == 0) break
        sumB += t * hist[t]
        val mB = sumB / wB
        val mF = (sumAll - sumB) / wF
        val varBetween = wB.toDouble() * wF * (mB - mF) * (mB - mF)
        if (varBetween > maxVar) {
            maxVar = varBetween
            th = t
        }
    }
    val out = IntArray(w * h)
    for (i in px.indices) out[i] = if (luminance(px[i]) <= th) Color.BLACK else Color.WHITE
    val res = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    res.setPixels(out, 0, w, 0, 0, w, h)
    return res
}
