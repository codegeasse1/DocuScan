package com.docuscan.app.scan

/**
 * Aspect-ratio choices for the crop step (ported from makeacopy's CropAspectRatio).
 *
 * AUTO/ORIGINAL keep the quad's own pixel-distance proportions; fixed entries
 * enforce a concrete short/long ratio at warp time; CUSTOM uses a user ratio.
 */
enum class CropAspectRatio(val label: String) {
    AUTO("Auto"),
    ORIGINAL("Original"),
    A3("A3"),
    A4("A4"),
    A5("A5"),
    US_LETTER("Letter"),
    LEGAL("Legal"),
    CUSTOM("Custom");

    private val DIN_A = 1.0 / Math.sqrt(2.0)
    private val LETTER = 8.5 / 11.0
    private val LEGAL_R = 8.5 / 14.0

    /** Short/long ratio for fixed entries; null for AUTO/ORIGINAL/CUSTOM. */
    fun shortOverLong(): Double? = when (this) {
        A3, A4, A5 -> DIN_A
        US_LETTER -> LETTER
        LEGAL -> LEGAL_R
        else -> null
    }

    companion object {
        fun fromName(name: String?, def: CropAspectRatio): CropAspectRatio =
            name?.let { n -> entries.firstOrNull { it.name == n } } ?: def
    }
}
