package com.docuscan.app.data

/**
 * Page formats for PDF export (ported from makeacopy's PageFormat).
 *
 * FIT_TO_IMAGE sizes the PDF page to the image's own aspect ratio (no
 * letterboxing, no data loss); the fixed formats scale the image to fit the
 * chosen paper size.
 */
enum class PageFormat(val label: String) {
    FIT_TO_IMAGE("Fit to image"),
    A4("A4"),
    US_LETTER("US Letter"),
    LEGAL("Legal");

    val isFixed: Boolean get() = this != FIT_TO_IMAGE

    /** Page size in PDF points (1/72 in). Returns long-edge-oriented W/H. */
    fun pageSizePts(imageW: Int, imageH: Int): Pair<Float, Float> {
        return when (this) {
            US_LETTER -> 612f to 792f
            LEGAL -> 612f to 1008f
            A4 -> 595f to 842f
            FIT_TO_IMAGE -> {
                val maxDim = 842f
                val aspect = imageW.toFloat() / imageH
                if (aspect >= 1f) maxDim to (maxDim / aspect)
                else (maxDim * aspect) to maxDim
            }
        }
    }

    /** Maximum pixel dimensions at a target DPI; null for FIT_TO_IMAGE. */
    fun pixelsForDpi(dpi: Int): Pair<Int, Int>? {
        if (!isFixed) return null
        val (wIn, hIn) = when (this) {
            US_LETTER -> 8.5f to 11.0f
            LEGAL -> 8.5f to 14.0f
            else -> 8.27f to 11.69f
        }
        return (wIn * dpi).toInt().coerceAtLeast(1) to (hIn * dpi).toInt().coerceAtLeast(1)
    }

    companion object {
        fun fromName(name: String?, def: PageFormat): PageFormat =
            name?.let { n -> entries.firstOrNull { it.name == n } } ?: def
    }
}

/**
 * Predefined PDF quality presets (ported from makeacopy's PdfQualityPreset).
 * Each defines a target DPI and whether output should be forced to grayscale.
 */
enum class PdfQualityPreset(val label: String, val targetDpi: Int, val forceGrayscale: Boolean) {
    HIGH("High (300 dpi)", 300, false),
    STANDARD("Standard (200 dpi)", 200, false),
    SMALL("Small (150 dpi)", 150, false),
    VERY_SMALL("Very small (110 dpi)", 110, false);

    companion object {
        fun fromName(name: String?, def: PdfQualityPreset): PdfQualityPreset =
            name?.let { n -> entries.firstOrNull { it.name == n } } ?: def
    }
}
