package com.docuscan.app.scan

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * DocQuadNet-256 postprocessing pipeline — direct port of makeacopy's
 * DocQuadLetterbox / DocQuadScore / DocQuadPostprocessor (Apache 2.0).
 *
 * The model consumes a 256x256 letterboxed RGB image and emits a [1,4,64,64]
 * corner-heatmap plus a [1,1,64,64] mask logit map. These helpers turn that
 * into a document quad in original-pixel coordinates (TL,TR,BR,BL).
 */

/** Letterbox mapping between the source image and the model's 256x256 input. */
class DocQuadLetterbox(
    val srcW: Int,
    val srcH: Int,
    val dstW: Int,
    val dstH: Int,
    val scale: Double,
    val offsetX: Double,
    val offsetY: Double
) {
    fun forward(x: Double, y: Double): DoubleArray = doubleArrayOf(x * scale + offsetX, y * scale + offsetY)
    fun inverse(x: Double, y: Double): DoubleArray = doubleArrayOf((x - offsetX) / scale, (y - offsetY) / scale)

    companion object {
        fun create(srcW: Int, srcH: Int, dstW: Int = 256, dstH: Int = 256): DocQuadLetterbox {
            require(srcW > 0 && srcH > 0 && dstW > 0 && dstH > 0)
            val s = min(dstW.toDouble() / srcW, dstH.toDouble() / srcH)
            val newW = srcW * s
            val newH = srcH * s
            return DocQuadLetterbox(srcW, srcH, dstW, dstH, s, (dstW - newW) / 2.0, (dstH - newH) / 2.0)
        }
    }
}

/** Deterministic quad geometry scoring (ported from DocQuadScore). */
object DocQuadScore {

    private fun requireQuad(quad: Array<DoubleArray>) {
        require(quad != null && quad.size == 4) { "quad must be double[4][2]" }
        for (p in quad) require(p != null && p.size == 2) { "quad must be double[4][2]" }
    }

    fun areaAbs(quad: Array<DoubleArray>): Double {
        requireQuad(quad)
        var s = 0.0
        for (i in 0..3) {
            val j = (i + 1) % 4
            s += quad[i][0] * quad[j][1] - quad[j][0] * quad[i][1]
        }
        return abs(0.5 * s)
    }

    fun perimeter(quad: Array<DoubleArray>): Double {
        requireQuad(quad)
        var p = 0.0
        for (i in 0..3) {
            val j = (i + 1) % 4
            p += hypot(quad[j][0] - quad[i][0], quad[j][1] - quad[i][1])
        }
        return p
    }

    fun edgeLengthMin(quad: Array<DoubleArray>): Double {
        requireQuad(quad)
        var m = Double.POSITIVE_INFINITY
        for (i in 0..3) {
            val j = (i + 1) % 4
            m = min(m, hypot(quad[j][0] - quad[i][0], quad[j][1] - quad[i][1]))
        }
        return m
    }

    fun edgeLengthMax(quad: Array<DoubleArray>): Double {
        requireQuad(quad)
        var m = 0.0
        for (i in 0..3) {
            val j = (i + 1) % 4
            m = max(m, hypot(quad[j][0] - quad[i][0], quad[j][1] - quad[i][1]))
        }
        return m
    }

    fun aspectLike(quad: Array<DoubleArray>): Double = edgeLengthMax(quad) / max(edgeLengthMin(quad), 1e-9)

    fun selfIntersects(quad: Array<DoubleArray>): Boolean {
        requireQuad(quad)
        return segmentsIntersect(quad[0], quad[1], quad[2], quad[3]) ||
            segmentsIntersect(quad[1], quad[2], quad[3], quad[0])
    }

    fun isConvex(quad: Array<DoubleArray>): Boolean {
        requireQuad(quad)
        val eps = 1e-9
        var sign = 0
        for (i in 0..3) {
            val j = (i + 1) % 4
            val k = (i + 2) % 4
            val cross = orient(quad[i], quad[j], quad[k])
            if (abs(cross) <= eps) return false
            val s = if (cross > 0.0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    fun oobSum(quad: Array<DoubleArray>, w: Double, h: Double, tolPx: Double): Double {
        requireQuad(quad)
        require(w > 0.0 && h > 0.0 && tolPx.isFinite() && tolPx >= 0.0)
        val left = -tolPx
        val top = -tolPx
        val right = (w - 1.0) + tolPx
        val bottom = (h - 1.0) + tolPx
        var s = 0.0
        for (i in 0..3) {
            s += oob1d(quad[i][0], left, right) + oob1d(quad[i][1], top, bottom)
        }
        return s
    }

    fun oobMax(quad: Array<DoubleArray>, w: Double, h: Double, tolPx: Double): Double {
        requireQuad(quad)
        require(w > 0.0 && h > 0.0 && tolPx.isFinite() && tolPx >= 0.0)
        val left = -tolPx
        val top = -tolPx
        val right = (w - 1.0) + tolPx
        val bottom = (h - 1.0) + tolPx
        var m = 0.0
        for (i in 0..3) {
            m = max(m, oob1d(quad[i][0], left, right) + oob1d(quad[i][1], top, bottom))
        }
        return m
    }

    private fun oob1d(v: Double, lo: Double, hi: Double): Double =
        if (v < lo) lo - v else if (v > hi) v - hi else 0.0

    private fun orient(a: DoubleArray, b: DoubleArray, c: DoubleArray): Double =
        (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])

    private fun onSegment(a: DoubleArray, b: DoubleArray, p: DoubleArray, eps: Double): Boolean {
        if (abs(orient(a, b, p)) > eps) return false
        return (min(a[0], b[0]) - eps <= p[0] && p[0] <= max(a[0], b[0]) + eps) &&
            (min(a[1], b[1]) - eps <= p[1] && p[1] <= max(a[1], b[1]) + eps)
    }

    private fun segmentsIntersect(a: DoubleArray, b: DoubleArray, c: DoubleArray, d: DoubleArray): Boolean {
        val eps = 1e-9
        val o1 = orient(a, b, c)
        val o2 = orient(a, b, d)
        val o3 = orient(c, d, a)
        val o4 = orient(c, d, b)
        val s1 = sign(o1, eps)
        val s2 = sign(o2, eps)
        val s3 = sign(o3, eps)
        val s4 = sign(o4, eps)
        if (s1 == 0 && onSegment(a, b, c, eps)) return true
        if (s2 == 0 && onSegment(a, b, d, eps)) return true
        if (s3 == 0 && onSegment(c, d, a, eps)) return true
        if (s4 == 0 && onSegment(c, d, b, eps)) return true
        return (s1 * s2 < 0) && (s3 * s4 < 0)
    }

    private fun sign(v: Double, eps: Double): Int = when {
        v > eps -> 1
        v < -eps -> -1
        else -> 0
    }
}

/**
 * Postprocessor for DocQuadNet-256 outputs. [maskLogits] is 64x64, [cornerHeatmaps]
 * is 4x64x64 (channel order TL,TR,BR,BL). Direct port of DocQuadPostprocessor.
 */
object DocQuadPostprocessor {

    enum class ChosenSource { CORNERS, MASK }

    enum class PeakMode { ARGMAX, REFINE_3X3, REFINE_5X5_QUADRATIC }

    class Result(
        val corners256: Array<DoubleArray>,
        val cornersOriginal: Array<DoubleArray>?,
        val maskProbGt05Count: Int,
        val maskProbMean: Double,
        val quadFromMask256: Array<DoubleArray>,
        val quadFromMaskOriginal: Array<DoubleArray>?,
        val quadFromMaskUsedFallback: Boolean,
        val chosenQuad256: Array<DoubleArray>,
        val chosenQuadOriginal: Array<DoubleArray>?,
        val chosenSource: ChosenSource,
        val penaltyCorners: Double,
        val penaltyMask: Double,
        val suspiciousForProduct: Boolean,
        val suspiciousReason: String?
    )

    class QuadFromMask(val quad256: Array<DoubleArray>, val usedFallback: Boolean)

    class MaskStats(val maskProbGt05Count: Int, val maskProbMean: Double)

    private val PEAK_SIGMA_THRESHOLD = 5.0
    private val MASK_DIFFUSE_MEAN_THRESHOLD = 0.45
    private val MASK_DIFFUSE_MIN_AREA = 100
    private val GEOMETRY_IMPLAUSIBLE_THRESHOLD = 1e4
    private val HARD_PENALTY_THRESHOLD = 1e5
    private val AGREEMENT_MAX_CORNER_DIST = 32.0
    private val MASK_SCORE_MARGIN = 50.0

    fun postprocess(
        cornerHeatmaps: Array<Array<FloatArray>>,
        maskLogits: Array<FloatArray>,
        lb: DocQuadLetterbox?,
        peakMode: PeakMode
    ): Result {
        val corners256 = corners64ToCorners256(cornerHeatmaps, peakMode)
        val ms = computeMaskStats(maskLogits)
        val qm = quadFromMask256(maskLogits, corners256)

        val pc = choosePath(corners256, qm.quad256, qm.usedFallback, maskLogits)

        val cornersOriginal = lb?.let { mapCorners256ToOriginal(corners256, it) }
        val quadOriginal = lb?.let { mapCorners256ToOriginal(qm.quad256, it) }
        val chosenOriginal = if (pc.chosenSource == ChosenSource.MASK) quadOriginal else cornersOriginal

        val suspiciousReason = evaluateSuspicious(cornerHeatmaps, ms, qm, pc)
        return Result(
            corners256, cornersOriginal, ms.maskProbGt05Count, ms.maskProbMean,
            qm.quad256, quadOriginal, qm.usedFallback,
            pc.chosenQuad256, chosenOriginal, pc.chosenSource,
            pc.penaltyCorners, pc.penaltyMask,
            suspiciousReason != null, suspiciousReason
        )
    }

    private fun evaluateSuspicious(
        cornerHeatmaps: Array<Array<FloatArray>>,
        ms: MaskStats,
        qm: QuadFromMask,
        pc: PathChoice
    ): String? {
        if (hasLowPeakMargin(cornerHeatmaps)) return "LOW_PEAK_MARGIN"
        if (ms.maskProbMean > MASK_DIFFUSE_MEAN_THRESHOLD && ms.maskProbGt05Count < MASK_DIFFUSE_MIN_AREA) {
            return "MASK_DIFFUSE"
        }
        if (qm.usedFallback && pc.penaltyCorners > GEOMETRY_IMPLAUSIBLE_THRESHOLD) {
            return "MASK_FALLBACK_AND_PCORNER"
        }
        if (!qm.usedFallback) {
            val maxDist = maxCornerDistance(pc.chosenQuad256, qm.quad256)
            if (pc.chosenSource == ChosenSource.CORNERS && maxDist > 64.0) return "DISAGREE_64PX"
        }
        val chosenPenalty = if (pc.chosenSource == ChosenSource.MASK) pc.penaltyMask else pc.penaltyCorners
        if (chosenPenalty >= GEOMETRY_IMPLAUSIBLE_THRESHOLD) return "GEOMETRY_IMPLAUSIBLE"
        return null
    }

    private fun hasLowPeakMargin(cornerHeatmaps: Array<Array<FloatArray>>): Boolean {
        for (c in 0..3) {
            val hm = cornerHeatmaps[c]
            var best = -Float.MAX_VALUE
            var sum = 0.0
            var n = 0
            for (y in 0..63) for (x in 0..63) {
                val v = hm[y][x]
                sum += v
                n++
                if (v > best) best = v
            }
            val mean = sum / max(n, 1)
            var sumSq = 0.0
            for (y in 0..63) for (x in 0..63) {
                val d = hm[y][x] - mean
                sumSq += d * d
            }
            val std = sqrt(sumSq / max(n, 1))
            if (std > 1e-6 && (best - mean) / std < PEAK_SIGMA_THRESHOLD) return true
        }
        return false
    }

    private class PathChoice(
        val chosenQuad256: Array<DoubleArray>,
        val chosenSource: ChosenSource,
        val penaltyCorners: Double,
        val penaltyMask: Double
    )

    private fun choosePath(
        quadCorners256: Array<DoubleArray>,
        quadFromMask256: Array<DoubleArray>,
        quadFromMaskUsedFallback: Boolean,
        maskLogits: Array<FloatArray>
    ): PathChoice {
        val pAGeom = quadPenaltyGeometry(quadCorners256)
        val pA = pAGeom + maskDisagreementPenaltyForCorners(quadCorners256, maskLogits)

        if (quadFromMaskUsedFallback) {
            return PathChoice(quadCorners256, ChosenSource.CORNERS, pA, Double.POSITIVE_INFINITY)
        }

        val pB = quadPenaltyGeometry(quadFromMask256)

        if (pAGeom >= HARD_PENALTY_THRESHOLD && pB < HARD_PENALTY_THRESHOLD) {
            return PathChoice(quadFromMask256, ChosenSource.MASK, pA, pB)
        }
        if (pB >= HARD_PENALTY_THRESHOLD) {
            return PathChoice(quadCorners256, ChosenSource.CORNERS, pA, pB)
        }

        val maxCornerDist = maxCornerDistance(quadCorners256, quadFromMask256)
        if (maxCornerDist > AGREEMENT_MAX_CORNER_DIST) {
            return PathChoice(quadCorners256, ChosenSource.CORNERS, pA, pB)
        }

        if (pB < pAGeom - MASK_SCORE_MARGIN) {
            return PathChoice(quadFromMask256, ChosenSource.MASK, pA, pB)
        }

        return PathChoice(quadCorners256, ChosenSource.CORNERS, pA, pB)
    }

    private fun maxCornerDistance(q1: Array<DoubleArray>, q2: Array<DoubleArray>): Double {
        if (q1.size != 4 || q2.size != 4) return Double.MAX_VALUE
        var m = 0.0
        for (i in 0..3) m = max(m, hypot(q1[i][0] - q2[i][0], q1[i][1] - q2[i][1]))
        return m
    }

    private fun quadPenaltyGeometry(quad256: Array<DoubleArray>): Double {
        if (quad256 == null || quad256.size != 4) return 1e6
        for (i in 0..3) {
            if (quad256[i].size != 2) return 1e6
            if (!quad256[i][0].isFinite() || !quad256[i][1].isFinite()) return 1e6
        }
        var penalty = 0.0
        val w = 256.0
        val h = 256.0
        val tol = 2.0
        val hard = 16.0
        val kSoft = 10.0
        val kHard = 1000.0

        val oobSum = DocQuadScore.oobSum(quad256, w, h, tol)
        if (oobSum > 0.0) penalty += oobSum * kSoft
        val oobMax = DocQuadScore.oobMax(quad256, w, h, tol)
        if (oobMax > hard) penalty += 1e5 + (oobMax - hard) * kHard

        if (DocQuadScore.selfIntersects(quad256)) penalty += 1e6
        if (!DocQuadScore.isConvex(quad256)) penalty += 1e6
        val areaAbs = DocQuadScore.areaAbs(quad256)
        if (!(areaAbs > 1.0)) penalty += 1e6

        val edgeMin = DocQuadScore.edgeLengthMin(quad256)
        val edgeMax = DocQuadScore.edgeLengthMax(quad256)
        if (edgeMin < 8.0) penalty += (8.0 - edgeMin) * 1000.0
        val r = edgeMax / max(edgeMin, 1e-9)
        if (r > 25.0) penalty += (r - 25.0) * 100.0
        return penalty
    }

    private fun maskDisagreementPenaltyForCorners(quadCorners256: Array<DoubleArray>, maskLogits: Array<FloatArray>): Double {
        val quad64 = Array(4) { DoubleArray(2) }
        for (i in 0..3) {
            quad64[i][0] = quadCorners256[i][0] / 4.0
            quad64[i][1] = quadCorners256[i][1] / 4.0
        }
        val grid = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56)
        var disagree = 0
        for (gy in grid) for (gx in grid) {
            val px = gx + 0.5
            val py = gy + 0.5
            val inQuad = pointInPolyInclusive(quad64, px, py)
            val inMask = sigmoid(maskLogits[gy][gx]) > 0.5
            if (inQuad != inMask) disagree++
        }
        return disagree * 10.0
    }

    private fun pointInPolyInclusive(poly: Array<DoubleArray>, px: Double, py: Double): Boolean {
        for (i in 0..3) {
            val j = (i + 1) % 4
            if (onSegment(poly[i][0], poly[i][1], poly[j][0], poly[j][1], px, py, 1e-9)) return true
        }
        var inside = false
        var i = 0
        var j = 3
        while (i < 4) {
            val xi = poly[i][0]; val yi = poly[i][1]
            val xj = poly[j][0]; val yj = poly[j][1]
            val intersect = ((yi > py) != (yj > py)) && (px < (xj - xi) * (py - yi) / (yj - yi) + xi)
            if (intersect) inside = !inside
            j = i++
        }
        return inside
    }

    private fun orient(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double): Double =
        (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)

    private fun onSegment(ax: Double, ay: Double, bx: Double, by: Double, px: Double, py: Double, eps: Double): Boolean {
        if (abs(orient(ax, ay, bx, by, px, py)) > eps) return false
        return (min(ax, bx) - eps <= px && px <= max(ax, bx) + eps) &&
            (min(ay, by) - eps <= py && py <= max(ay, by) + eps)
    }

    /** Minimal Mask→Quad path (PCA oriented rectangle). */
    fun quadFromMask256(maskLogits: Array<FloatArray>, fallbackCorners256: Array<DoubleArray>): QuadFromMask {
        var maskCount = 0
        var sumX = 0.0
        var sumY = 0.0
        for (y in 0..63) for (x in 0..63) {
            if (sigmoid(maskLogits[y][x]) > 0.5) {
                maskCount++
                sumX += (x + 0.5)
                sumY += (y + 0.5)
            }
        }
        if (maskCount == 0) return QuadFromMask(fallbackCorners256, true)

        val cx = sumX / maskCount
        val cy = sumY / maskCount
        if (!cx.isFinite() || !cy.isFinite()) return QuadFromMask(fallbackCorners256, true)

        var sxx = 0.0; var sxy = 0.0; var syy = 0.0
        for (y in 0..63) for (x in 0..63) {
            if (sigmoid(maskLogits[y][x]) > 0.5) {
                val dx = (x + 0.5) - cx
                val dy = (y + 0.5) - cy
                sxx += dx * dx; sxy += dx * dy; syy += dy * dy
            }
        }
        sxx /= maskCount; sxy /= maskCount; syy /= maskCount

        val trace = sxx + syy
        if (!trace.isFinite() || trace < 1e-12) return QuadFromMask(fallbackCorners256, true)

        val det = sxx * syy - sxy * sxy
        val disc = sqrt(max(0.0, trace * trace / 4.0 - det))
        val lambda1 = trace / 2.0 + disc

        val eps = 1e-12
        var v1x: Double
        var v1y: Double
        if (abs(sxy) > eps) {
            v1x = lambda1 - syy
            v1y = sxy
        } else {
            if (sxx >= syy) { v1x = 1.0; v1y = 0.0 } else { v1x = 0.0; v1y = 1.0 }
        }
        val n = hypot(v1x, v1y)
        if (n == 0.0 || !n.isFinite()) return QuadFromMask(fallbackCorners256, true)
        v1x /= n
        v1y /= n

        val v2x = -v1y
        val v2y = v1x

        var uMin = Double.POSITIVE_INFINITY
        var uMax = Double.NEGATIVE_INFINITY
        var vMin = Double.POSITIVE_INFINITY
        var vMax = Double.NEGATIVE_INFINITY
        for (y in 0..63) for (x in 0..63) {
            if (sigmoid(maskLogits[y][x]) > 0.5) {
                val px = (x + 0.5) - cx
                val py = (y + 0.5) - cy
                val u = px * v1x + py * v1y
                val v = px * v2x + py * v2y
                uMin = min(uMin, u); uMax = max(uMax, u)
                vMin = min(vMin, v); vMax = max(vMax, v)
            }
        }
        if (!(uMin.isFinite() && uMax.isFinite() && vMin.isFinite() && vMax.isFinite())) {
            return QuadFromMask(fallbackCorners256, true)
        }
        if (uMax - uMin < 1e-12 || vMax - vMin < 1e-12) return QuadFromMask(fallbackCorners256, true)

        val quad64 = Array(4) { DoubleArray(2) }
        quad64[0] = doubleArrayOf(cx + uMax * v1x + vMax * v2x, cy + uMax * v1y + vMax * v2y)
        quad64[1] = doubleArrayOf(cx + uMin * v1x + vMax * v2x, cy + uMin * v1y + vMax * v2y)
        quad64[2] = doubleArrayOf(cx + uMin * v1x + vMin * v2x, cy + uMin * v1y + vMin * v2y)
        quad64[3] = doubleArrayOf(cx + uMax * v1x + vMin * v2x, cy + uMax * v1y + vMin * v2y)

        val canonical = canonicalizeQuadOrderV1(quad64)

        val quad256 = Array(4) { DoubleArray(2) }
        for (i in 0..3) {
            quad256[i][0] = canonical[i][0] * 4.0
            quad256[i][1] = canonical[i][1] * 4.0
        }
        return QuadFromMask(quad256, false)
    }

    private fun canonicalizeQuadOrderV1(pts: Array<DoubleArray>): Array<DoubleArray> {
        var cx = 0.0
        var cy = 0.0
        for (i in 0..3) { cx += pts[i][0]; cy += pts[i][1] }
        cx /= 4.0
        cy /= 4.0

        val ordered = IntArray(4) { it }
        for (i in 0..3) for (j in i + 1..3) {
            val a = ordered[i]
            val b = ordered[j]
            val angA = atan2(pts[a][1] - cy, pts[a][0] - cx)
            val angB = atan2(pts[b][1] - cy, pts[b][0] - cx)
            val swap = angB < angA || (angB == angA && b < a)
            if (swap) {
                ordered[i] = b
                ordered[j] = a
            }
        }

        var tlPos = 0
        var bestSum = Double.POSITIVE_INFINITY
        for (k in 0..3) {
            val idx = ordered[k]
            val s = pts[idx][0] + pts[idx][1]
            if (s < bestSum || (s == bestSum && k < tlPos)) {
                bestSum = s
                tlPos = k
            }
        }

        return Array(4) { i ->
            val src = ordered[(tlPos + i) % 4]
            doubleArrayOf(pts[src][0], pts[src][1])
        }
    }

    fun corners64ToCorners256(cornerHeatmaps: Array<Array<FloatArray>>, peakMode: PeakMode): Array<DoubleArray> =
        when (peakMode) {
            PeakMode.ARGMAX -> argmaxCorners64ToCorners256(cornerHeatmaps)
            PeakMode.REFINE_3X3 -> refineCorners64ToCorners256_3x3(cornerHeatmaps)
            PeakMode.REFINE_5X5_QUADRATIC -> refineCorners64ToCorners256_5x5Quadratic(cornerHeatmaps)
        }

    fun argmaxCorners64ToCorners256(cornerHeatmaps: Array<Array<FloatArray>>): Array<DoubleArray> {
        val out = Array(4) { DoubleArray(2) }
        for (c in 0..3) {
            val hm = cornerHeatmaps[c]
            var best = -Float.MAX_VALUE
            var bestX = 0
            var bestY = 0
            for (y in 0..63) for (x in 0..63) {
                val v = hm[y][x]
                if (v > best) { best = v; bestX = x; bestY = y }
            }
            out[c][0] = (bestX + 0.5) * 4.0
            out[c][1] = (bestY + 0.5) * 4.0
        }
        return out
    }

    fun refineCorners64ToCorners256_3x3(cornerHeatmaps: Array<Array<FloatArray>>): Array<DoubleArray> {
        val out = Array(4) { DoubleArray(2) }
        for (c in 0..3) {
            val hm = cornerHeatmaps[c]
            var best = -Float.MAX_VALUE
            var bestX = 0
            var bestY = 0
            for (y in 0..63) for (x in 0..63) {
                val v = hm[y][x]
                if (v > best) { best = v; bestX = x; bestY = y }
            }
            val x0 = max(0, bestX - 1)
            val x1 = min(63, bestX + 1)
            val y0 = max(0, bestY - 1)
            val y1 = min(63, bestY + 1)
            var maxLogit = Double.NEGATIVE_INFINITY
            for (y in y0..y1) for (x in x0..x1) maxLogit = max(maxLogit, hm[y][x].toDouble())
            var sumW = 0.0
            var sumX = 0.0
            var sumY = 0.0
            for (y in y0..y1) for (x in x0..x1) {
                val w = exp(hm[y][x].toDouble() - maxLogit)
                sumW += w
                sumX += w * (x + 0.5)
                sumY += w * (y + 0.5)
            }
            val x64: Double
            val y64: Double
            if (sumW == 0.0 || !sumW.isFinite()) {
                x64 = bestX + 0.5
                y64 = bestY + 0.5
            } else {
                x64 = sumX / sumW
                y64 = sumY / sumW
            }
            out[c][0] = x64 * 4.0
            out[c][1] = y64 * 4.0
        }
        return out
    }

    fun refineCorners64ToCorners256_5x5Quadratic(cornerHeatmaps: Array<Array<FloatArray>>): Array<DoubleArray> {
        val out = Array(4) { DoubleArray(2) }
        for (c in 0..3) {
            val hm = cornerHeatmaps[c]
            var best = -Float.MAX_VALUE
            var bestX = 0
            var bestY = 0
            for (y in 0..63) for (x in 0..63) {
                val v = hm[y][x]
                if (v > best) { best = v; bestX = x; bestY = y }
            }

            var dx = 0.0
            var dxValid = false
            if (bestX > 0 && bestX < 63) {
                val l = hm[bestY][bestX - 1].toDouble()
                val cV = hm[bestY][bestX].toDouble()
                val r = hm[bestY][bestX + 1].toDouble()
                val denom = l - 2.0 * cV + r
                if (denom < -1e-12) {
                    dx = (0.5 * (l - r) / denom).coerceIn(-0.5, 0.5)
                    dxValid = true
                }
            }
            var dy = 0.0
            var dyValid = false
            if (bestY > 0 && bestY < 63) {
                val t = hm[bestY - 1][bestX].toDouble()
                val cV = hm[bestY][bestX].toDouble()
                val b = hm[bestY + 1][bestX].toDouble()
                val denom = t - 2.0 * cV + b
                if (denom < -1e-12) {
                    dy = (0.5 * (t - b) / denom).coerceIn(-0.5, 0.5)
                    dyValid = true
                }
            }

            val x64: Double
            val y64: Double
            if (dxValid || dyValid) {
                x64 = bestX + 0.5 + dx
                y64 = bestY + 0.5 + dy
            } else {
                val x0 = max(0, bestX - 2)
                val x1 = min(63, bestX + 2)
                val y0 = max(0, bestY - 2)
                val y1 = min(63, bestY + 2)
                var maxLogit = Double.NEGATIVE_INFINITY
                for (y in y0..y1) for (x in x0..x1) maxLogit = max(maxLogit, hm[y][x].toDouble())
                var sumW = 0.0
                var sumX = 0.0
                var sumY = 0.0
                for (y in y0..y1) for (x in x0..x1) {
                    val w = exp(hm[y][x].toDouble() - maxLogit)
                    sumW += w
                    sumX += w * (x + 0.5)
                    sumY += w * (y + 0.5)
                }
                if (sumW == 0.0 || !sumW.isFinite()) {
                    x64 = bestX + 0.5
                    y64 = bestY + 0.5
                } else {
                    x64 = sumX / sumW
                    y64 = sumY / sumW
                }
            }
            out[c][0] = x64 * 4.0
            out[c][1] = y64 * 4.0
        }
        return out
    }

    fun computeMaskStats(maskLogits: Array<FloatArray>): MaskStats {
        var count = 0
        var sumProb = 0.0
        for (y in 0..63) for (x in 0..63) {
            val prob = sigmoid(maskLogits[y][x])
            sumProb += prob
            if (prob > 0.5) count++
        }
        return MaskStats(count, sumProb / (64.0 * 64.0))
    }

    fun mapCorners256ToOriginal(corners256: Array<DoubleArray>, lb: DocQuadLetterbox): Array<DoubleArray> =
        Array(4) { i ->
            val p = lb.inverse(corners256[i][0], corners256[i][1])
            doubleArrayOf(p[0], p[1])
        }

    private fun sigmoid(x: Float): Double = 1.0 / (1.0 + exp(-x.toDouble()))
}
