package com.docuscan.app.scan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint

data class FilterOption(val id: String, val label: String)

val FILTERS = listOf(
    FilterOption("original", "Original"),
    FilterOption("enhance", "Enhance"),
    FilterOption("bw", "B&W"),
    FilterOption("grayscale", "Gray"),
    FilterOption("sepia", "Sepia"),
    FilterOption("invert", "Invert"),
    FilterOption("warm", "Warm"),
    FilterOption("cool", "Cool"),
    FilterOption("vivid", "Vivid"),
    FilterOption("faded", "Faded"),
    FilterOption("crisp", "Crisp"),
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
    "enhance" -> ColorMatrix().apply {
        setSaturation(1.28f)
        postConcat(contrastMatrix(1.12f))
    }
    "grayscale" -> ColorMatrix().apply { setSaturation(0f) }
    "bw" -> ColorMatrix().apply {
        setSaturation(0f)
        postConcat(contrastMatrix(9f))
    }
    "sepia" -> ColorMatrix(
        floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f
        )
    )
    "invert" -> ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        )
    )
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
    val base = baseMatrix(filterId)
    val needsWork = base != null || contrast != 1f || brightness != 0f
    val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val c = Canvas(out)
    if (needsWork) {
        val combined = ColorMatrix()
        if (base != null) combined.set(base)
        if (contrast != 1f) combined.postConcat(contrastMatrix(contrast))
        if (brightness != 0f) combined.postConcat(translateMatrix(brightness * 25f))
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(combined) }
        c.drawBitmap(src, 0f, 0f, paint)
    } else {
        c.drawBitmap(src, 0f, 0f, null)
    }
    return out
}
