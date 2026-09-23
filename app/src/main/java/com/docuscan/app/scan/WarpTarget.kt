package com.docuscan.app.scan

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Warp target-size computation — direct port of makeacopy's
 * OpenCVUtils.computeWarpTargetSize* / estimateProjectiveAspectRatio.
 *
 * WarpMode semantics:
 *  - AUTO_PROJECTIVE: Zhang & He (2006) projective aspect-ratio estimate with
 *    heuristic fallback (makeacopy's stage-A default).
 *  - LEGACY_HEURISTIC: plain pixel-distance heuristic (no projective correction).
 *  - FIXED_RATIO: enforce a user-supplied short/long edge ratio.
 */
object WarpTarget {

    enum class WarpMode { AUTO_PROJECTIVE, LEGACY_HEURISTIC, FIXED_RATIO }

    /** Corner order TL,TR,BR,BL in image pixel coords. */
    fun compute(
        corners: Array<android.graphics.PointF>,
        mode: WarpMode,
        shortOverLong: Double?,
        srcWidth: Int,
        srcHeight: Int
    ): Pair<Int, Int> {
        if (corners.size != 4) return 1 to 1
        return when (mode) {
            WarpMode.FIXED_RATIO -> {
                val ratio = shortOverLong ?: return 1 to 1
                computeWarpTargetSizeForFixedRatio(corners, ratio)
            }
            WarpMode.LEGACY_HEURISTIC -> computeWarpTargetSize(corners)
            WarpMode.AUTO_PROJECTIVE -> computeWarpTargetSizeProjective(corners, srcWidth, srcHeight)
        }
    }

    /** Fixed short/long ratio; the longer pixel edge anchors the output resolution. */
    private fun computeWarpTargetSizeForFixedRatio(corners: Array<android.graphics.PointF>, shortOverLong: Double): Pair<Int, Int> {
        if (!(shortOverLong > 0.0) || shortOverLong > 1.0 || !shortOverLong.isFinite()) return 1 to 1
        val wTop = distance(corners[0], corners[1])
        val wBottom = distance(corners[2], corners[3])
        val hLeft = distance(corners[0], corners[3])
        val hRight = distance(corners[1], corners[2])
        val meanW = 0.5 * (wTop + wBottom)
        val meanH = 0.5 * (hLeft + hRight)
        val longPx = max(max(wTop, wBottom), max(hLeft, hRight))
        if (longPx < 1.0) return 1 to 1
        val landscapeQuad = meanW >= meanH
        val w: Int
        val h: Int
        if (landscapeQuad) {
            w = Math.round(longPx).toInt()
            h = Math.round(longPx * shortOverLong).toInt()
        } else {
            h = Math.round(longPx).toInt()
            w = Math.round(longPx * shortOverLong).toInt()
        }
        return max(1, w) + 1 to max(1, h) + 1
    }

    /** Legacy pixel-distance heuristic (v3.7.1). */
    private fun computeWarpTargetSize(corners: Array<android.graphics.PointF>): Pair<Int, Int> {
        val wTop = distance(corners[0], corners[1])
        val wBottom = distance(corners[2], corners[3])
        val hLeft = distance(corners[0], corners[3])
        val hRight = distance(corners[1], corners[2])
        val w = max(1, Math.round(max(wTop, wBottom)).toInt() + 1)
        val h = max(1, Math.round(max(hLeft, hRight)).toInt() + 1)
        return w to h
    }

    /**
     * AUTO output sizing for a manually/automatically selected document quad.
     *
     * The destination rectangle must have roughly the same aspect ratio as the
     * selected quadrilateral. A previous projective-aspect implementation could
     * return a wildly different ratio for ordinary phone photos (for example
     * ~0.3 for a ~0.7 portrait page), which made the perspective warp squeeze
     * the final scan horizontally even though the crop overlay looked correct.
     *
     * OpenCV's standard scanner pipeline uses the measured top/bottom and
     * left/right edge lengths to choose the warp canvas. We do the same here,
     * using the mean edge lengths for stability and the longest side for output
     * resolution. The homography still performs the actual perspective
     * correction; this function only chooses the destination canvas shape.
     */
    private fun computeWarpTargetSizeProjective(
        corners: Array<android.graphics.PointF>,
        srcWidth: Int,
        srcHeight: Int
    ): Pair<Int, Int> {
        val wTop = distance(corners[0], corners[1])
        val wBottom = distance(corners[2], corners[3])
        val hLeft = distance(corners[0], corners[3])
        val hRight = distance(corners[1], corners[2])
        val meanW = 0.5 * (wTop + wBottom)
        val meanH = 0.5 * (hLeft + hRight)
        if (!meanW.isFinite() || !meanH.isFinite() || meanW < 1.0 || meanH < 1.0) {
            return computeWarpTargetSize(corners)
        }

        // Keep AUTO tied to the geometry the user can actually see. This also
        // avoids an accidental portrait/landscape flip when detection is skewed.
        val longPx = max(meanW, meanH)
        val aspect = (meanW / meanH).coerceIn(0.05, 20.0)
        val landscape = meanW >= meanH
        val outW: Int
        val outH: Int
        if (landscape) {
            outW = Math.round(longPx).toInt()
            outH = Math.round(longPx / aspect).toInt()
        } else {
            outH = Math.round(longPx).toInt()
            outW = Math.round(longPx * aspect).toInt()
        }
        return max(2, outW + 1) to max(2, outH + 1)
    }

    /**
     * Zhang & He (2006) closed-form estimate of the true W/H ratio of the
     * rectangle whose perspective projection is given by the four corners.
     * Returns null when degenerate.
     */
    fun estimateProjectiveAspectRatio(corners: Array<android.graphics.PointF>, srcWidth: Int, srcHeight: Int): Double? {
        if (corners.size != 4 || srcWidth <= 0 || srcHeight <= 0) return null
        val cx = srcWidth * 0.5
        val cy = srcHeight * 0.5
        val p0 = doubleArrayOf(corners[0].x - cx, corners[0].y - cy)
        val p1 = doubleArrayOf(corners[1].x - cx, corners[1].y - cy)
        val p2 = doubleArrayOf(corners[2].x - cx, corners[2].y - cy)
        val p3 = doubleArrayOf(corners[3].x - cx, corners[3].y - cy)

        val k2num = det3(p0[0], p0[1], p3[0], p3[1], p2[0], p2[1])
        val k2den = det3(p1[0], p1[1], p3[0], p3[1], p2[0], p2[1])
        val k3num = det3(p0[0], p0[1], p1[0], p1[1], p2[0], p2[1])
        val k3den = det3(p3[0], p3[1], p1[0], p1[1], p2[0], p2[1])
        if (Math.abs(k2den) < 1e-9 || Math.abs(k3den) < 1e-9) return null
        val k2 = k2num / k2den
        val k3 = k3num / k3den

        val n2x = k2 * p1[0] - p0[0]
        val n2y = k2 * p1[1] - p0[1]
        val n2z = k2 - 1.0
        val n3x = k3 * p3[0] - p0[0]
        val n3y = k3 * p3[1] - p0[1]
        val n3z = k3 - 1.0

        if (Math.abs(n2z) < 1e-6 || Math.abs(n3z) < 1e-6) return null

        var fSquared = -(n2x * n3x + n2y * n3y) / (n2z * n3z)
        if (!fSquared.isFinite() || fSquared <= 0.0) {
            val f = max(srcWidth, srcHeight).toDouble()
            fSquared = f * f
        }

        val num = n2x * n2x + n2y * n2y + n2z * n2z * fSquared
        val den = n3x * n3x + n3y * n3y + n3z * n3z * fSquared
        if (den < 1e-12) return null
        val ratioSq = num / den
        if (!ratioSq.isFinite() || ratioSq <= 0.0) return null
        return sqrt(ratioSq)
    }

    /** 3x3 determinant with rows (a1,a2,a3), (b1,b2,b3), (c1,c2,c3) where z is implicit 1. */
    private fun det3(a1: Double, a2: Double, b1: Double, b2: Double, c1: Double, c2: Double): Double =
        a1 * (b2 * 1 - 1 * c2) - a2 * (b1 * 1 - 1 * c1) + 1 * (b1 * c2 - b2 * c1)

    private fun distance(a: android.graphics.PointF, b: android.graphics.PointF): Double =
        hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble())
}
