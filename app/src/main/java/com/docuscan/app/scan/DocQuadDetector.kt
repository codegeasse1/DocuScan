package com.docuscan.app.scan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class Source { DOCQUAD, OPENCV, FALLBACK }

/**
 * Result of corner detection in original-bitmap coordinates (TL,TR,BR,BL).
 * Port of makeacopy's DetectionResult.
 */
class DetectionResult(
    val success: Boolean,
    val source: Source,
    val cornersOriginalTLTRBRBL: Array<DoubleArray>?
) {
    companion object {
        fun success(source: Source, corners: Array<DoubleArray>): DetectionResult =
            DetectionResult(true, source, corners)

        fun fail(source: Source): DetectionResult = DetectionResult(false, source, null)
    }
}

/**
 * DocQuadNet-256 production adapter — port of makeacopy's DocQuadDetector.
 * Letterboxes the bitmap, runs ONNX, post-processes, and validates the quad.
 */
object DocQuadDetector {

    private val LETTERBOX_PAD_COLOR = 0xFF808080.toInt()

    fun detect(src: Bitmap): DetectionResult {
        var in256: Bitmap? = null
        try {
            val srcW = src.width
            val srcH = src.height
            if (srcW <= 0 || srcH <= 0) return DetectionResult.fail(Source.DOCQUAD)

            val lb = DocQuadLetterbox.create(srcW, srcH, DocQuadOrtRunner.IN_W, DocQuadOrtRunner.IN_H)
            in256 = renderLetterbox256(src, lb)
            val input = bitmapToNchwFloat01(in256)

            val runner = DocQuadOrtRunner.getInstance(AppCtx.app)
            val outputs = runner.run(input)
            val mask = outputs.maskLogits
            val corners = outputs.cornerHeatmaps

            val r = DocQuadPostprocessor.postprocess(
                corners, mask, lb, DocQuadPostprocessor.PeakMode.REFINE_5X5_QUADRATIC
            )
            val chosen = r.chosenQuadOriginal
            if (chosen == null || chosen.size != 4) return DetectionResult.fail(Source.DOCQUAD)

            if (!isValidQuad(chosen, srcW, srcH)) return DetectionResult.fail(Source.DOCQUAD)
            return DetectionResult.success(Source.DOCQUAD, chosen)
        } catch (t: Throwable) {
            return DetectionResult.fail(Source.DOCQUAD)
        } finally {
            try {
                if (in256 != null && !in256.isRecycled) in256.recycle()
            } catch (_: Throwable) {
            }
        }
    }

    /** Preprocessing exactly like training: RGB, 0..1, NCHW float32. */
    private fun bitmapToNchwFloat01(bmp: Bitmap): FloatArray {
        val w = bmp.width
        val h = bmp.height
        if (w != DocQuadOrtRunner.IN_W || h != DocQuadOrtRunner.IN_H) {
            throw IllegalArgumentException("bitmap must be 256x256")
        }
        val hw = h * w
        val out = FloatArray(3 * hw)
        val px = IntArray(hw)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = px[y * w + x]
                val r = ((c shr 16) and 0xFF) / 255.0f
                val g = ((c shr 8) and 0xFF) / 255.0f
                val b = (c and 0xFF) / 255.0f
                val idx = y * w + x
                out[idx] = r
                out[hw + idx] = g
                out[2 * hw + idx] = b
            }
        }
        return out
    }

    private fun renderLetterbox256(src: Bitmap, lb: DocQuadLetterbox): Bitmap {
        val out = Bitmap.createBitmap(DocQuadOrtRunner.IN_W, DocQuadOrtRunner.IN_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(LETTERBOX_PAD_COLOR)
        val dst = RectF(
            lb.offsetX.toFloat(),
            lb.offsetY.toFloat(),
            (lb.offsetX + lb.srcW * lb.scale).toFloat(),
            (lb.offsetY + lb.srcH * lb.scale).toFloat()
        )
        val paint = android.graphics.Paint().apply {
            isFilterBitmap = true
            isDither = true
            isAntiAlias = true
        }
        canvas.drawBitmap(src, null, dst, paint)
        return out
    }

    private fun isFinite(v: Double) = !v.isNaN() && !v.isInfinite()

    private fun isValidQuad(c: Array<DoubleArray>, w: Int, h: Int): Boolean {
        if (c.size != 4) return false
        for (i in 0..3) {
            if (c[i].size != 2) return false
            val x = c[i][0]
            val y = c[i][1]
            if (!isFinite(x) || !isFinite(y)) return false
            if (x < -w * 0.25 || x > w * 1.25) return false
            if (y < -h * 0.25 || y > h * 1.25) return false
        }
        return isConvexTLTRBRBL(c)
    }

    /** Strictly convex quad traversed clockwise in image coords (y down). */
    fun isConvexTLTRBRBL(c: Array<DoubleArray>): Boolean {
        if (c.size != 4) return false
        var prevSign = 0.0
        for (i in 0..3) {
            val a = c[i]
            val b = c[(i + 1) % 4]
            val d = c[(i + 2) % 4]
            if (a.size != 2 || b.size != 2 || d.size != 2) return false
            val abx = b[0] - a[0]
            val aby = b[1] - a[1]
            val bdx = d[0] - b[0]
            val bdy = d[1] - b[1]
            val cross = abx * bdy - aby * bdx
            if (!isFinite(cross) || cross == 0.0) return false
            val sign = Math.signum(cross)
            if (i == 0) {
                if (sign < 0.0) return false
                prevSign = sign
            } else if (sign != prevSign) {
                return false
            }
        }
        return true
    }
}

/** Hard geometric gates a detected quad must pass (port of QuadPlausibility). */
object QuadPlausibility {

    const val MIN_AREA_RATIO = 0.06f
    const val MIN_EDGE_PX = 24f
    const val OOB_TOL_PX = 8f
    const val OOB_SUM_MAX = 40f
    const val ASPECT_LIKE_MAX = 6.0f

    class Result(
        val plausible: Boolean,
        val isConvex: Boolean,
        val noSelfIntersection: Boolean,
        val meetsMinArea: Boolean,
        val withinBounds: Boolean,
        val aspectOk: Boolean
    )

    fun check(quad: Array<android.graphics.PointF>?, imageWidth: Int, imageHeight: Int): Result {
        if (quad == null || quad.size != 4 || imageWidth <= 0 || imageHeight <= 0) {
            return Result(false, false, false, false, false, false)
        }
        for (p in quad) {
            if (p == null) return Result(false, false, false, false, false, false)
        }

        val isConvex = isConvex(quad)
        val noSelfIntersection = !selfIntersects(quad)

        val imageArea = imageWidth.toFloat() * imageHeight
        val quadArea = Math.abs(shoelaceArea(quad))
        var meetsMinArea = quadArea / imageArea >= MIN_AREA_RATIO
        if (minEdgeLength(quad) < MIN_EDGE_PX) meetsMinArea = false

        val oobSum = computeOobSum(quad, imageWidth, imageHeight, OOB_TOL_PX)
        val withinBounds = oobSum <= OOB_SUM_MAX

        val aspectRatio = computeAspectRatio(quad)
        val aspectOk = aspectRatio <= ASPECT_LIKE_MAX

        return Result(
            isConvex && noSelfIntersection && meetsMinArea && withinBounds && aspectOk,
            isConvex, noSelfIntersection, meetsMinArea, withinBounds, aspectOk
        )
    }

    fun isPlausible(quad: Array<android.graphics.PointF>?, imageWidth: Int, imageHeight: Int): Boolean =
        check(quad, imageWidth, imageHeight).plausible

    private fun isConvex(quad: Array<android.graphics.PointF>): Boolean {
        var positive: Boolean? = null
        for (i in 0..3) {
            val a = quad[i]
            val b = quad[(i + 1) % 4]
            val c = quad[(i + 2) % 4]
            val cross = crossProduct(a, b, c)
            if (Math.abs(cross) < 1e-6f) continue
            val isPositive = cross > 0
            if (positive == null) positive = isPositive
            else if (positive != isPositive) return false
        }
        return true
    }

    private fun selfIntersects(quad: Array<android.graphics.PointF>): Boolean =
        segmentsIntersect(quad[0], quad[1], quad[2], quad[3]) ||
            segmentsIntersect(quad[1], quad[2], quad[3], quad[0])

    private fun shoelaceArea(quad: Array<android.graphics.PointF>): Float {
        var sum = 0f
        for (i in 0..3) {
            val cur = quad[i]
            val next = quad[(i + 1) % 4]
            sum += cur.x * next.y - next.x * cur.y
        }
        return sum / 2f
    }

    private fun minEdgeLength(quad: Array<android.graphics.PointF>): Float {
        var minLen = Float.MAX_VALUE
        for (i in 0..3) {
            val a = quad[i]
            val b = quad[(i + 1) % 4]
            minLen = min(minLen, distance(a, b))
        }
        return minLen
    }

    private fun computeOobSum(quad: Array<android.graphics.PointF>, iw: Int, ih: Int, tol: Float): Float {
        var sum = 0f
        for (p in quad) {
            if (p.x < -tol) sum += Math.abs(p.x + tol)
            if (p.x > iw + tol) sum += p.x - iw - tol
            if (p.y < -tol) sum += Math.abs(p.y + tol)
            if (p.y > ih + tol) sum += p.y - ih - tol
        }
        return sum
    }

    private fun computeAspectRatio(quad: Array<android.graphics.PointF>): Float {
        val top = distance(quad[0], quad[1])
        val bottom = distance(quad[3], quad[2])
        val left = distance(quad[0], quad[3])
        val right = distance(quad[1], quad[2])
        val avgW = (top + bottom) / 2f
        val avgH = (left + right) / 2f
        if (avgW < 1f || avgH < 1f) return Float.MAX_VALUE
        val ratio = avgW / avgH
        return if (ratio > 1f) ratio else 1f / ratio
    }

    private fun crossProduct(a: android.graphics.PointF, b: android.graphics.PointF, c: android.graphics.PointF): Float {
        val abx = b.x - a.x
        val aby = b.y - a.y
        val bcx = c.x - b.x
        val bcy = c.y - b.y
        return abx * bcy - aby * bcx
    }

    private fun distance(a: android.graphics.PointF, b: android.graphics.PointF): Float =
        Math.sqrt(((b.x - a.x) * (b.x - a.x) + (b.y - a.y) * (b.y - a.y)).toDouble()).toFloat()

    private fun segmentsIntersect(p1: android.graphics.PointF, p2: android.graphics.PointF, p3: android.graphics.PointF, p4: android.graphics.PointF): Boolean {
        val d1 = direction(p3, p4, p1)
        val d2 = direction(p3, p4, p2)
        val d3 = direction(p1, p2, p3)
        val d4 = direction(p1, p2, p4)
        if (((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0))) return true
        if (d1 == 0f && onSegment(p3, p4, p1)) return true
        if (d2 == 0f && onSegment(p3, p4, p2)) return true
        if (d3 == 0f && onSegment(p1, p2, p3)) return true
        if (d4 == 0f && onSegment(p1, p2, p4)) return true
        return false
    }

    private fun direction(pi: android.graphics.PointF, pj: android.graphics.PointF, pk: android.graphics.PointF): Float =
        (pk.x - pi.x) * (pj.y - pi.y) - (pj.x - pi.x) * (pk.y - pi.y)

    private fun onSegment(pi: android.graphics.PointF, pj: android.graphics.PointF, pk: android.graphics.PointF): Boolean =
        min(pi.x, pj.x) <= pk.x && pk.x <= max(pi.x, pj.x) &&
            min(pi.y, pj.y) <= pk.y && pk.y <= max(pi.y, pj.y)
}

/**
 * Gradient-based refinement of a coarse document quad — port of makeacopy's
 * EdgeSnapCornerRefiner. Snaps each quad edge to the strongest image gradients
 * in the full-resolution image (with subpixel parabolic refinement + TLS line
 * fit + outlier reweighting), then re-intersects the fitted lines.
 *
 * Never makes the result worse: on any failure the input quad is returned.
 */
object EdgeSnapCornerRefiner {

    private const val WORK_MAX_EDGE = 1600
    private const val SAMPLES_PER_EDGE = 48
    private const val EDGE_END_MARGIN = 0.10
    private const val MIN_VALID_FRACTION = 0.5
    private const val MIN_GRADIENT_MAGNITUDE = 40.0
    private const val NEAREST_PEAK_ACCEPT_FRACTION = 0.5
    private const val MAX_MEDIAN_RESIDUAL = 3.0
    private const val SEARCH_RADIUS_FRACTION = 0.02
    private const val MAX_CORNER_SHIFT_FRACTION = 0.03

    /** Refines {TL,TR,BR,BL} in original-pixel coords; returns the input array if skipped. */
    fun refine(src: Bitmap?, quadTLTRBRBL: Array<DoubleArray>): Array<DoubleArray> {
        if (src == null || !isPlausibleQuad(quadTLTRBRBL)) return quadTLTRBRBL
        var rgba: Mat? = null
        var gray: Mat? = null
        var gx: Mat? = null
        var gy: Mat? = null
        try {
            val srcW = src.width
            val srcH = src.height
            if (srcW <= 2 || srcH <= 2) return quadTLTRBRBL

            val scale = min(1.0, WORK_MAX_EDGE.toDouble() / max(srcW, srcH))

            rgba = Mat()
            Utils.bitmapToMat(src, rgba)
            gray = Mat()
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            rgba.release()
            rgba = null

            if (scale < 1.0) {
                val w = max(2, Math.round(srcW * scale).toInt())
                val h = max(2, Math.round(srcH * scale).toInt())
                Imgproc.resize(gray, gray, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            }
            Imgproc.GaussianBlur(gray, gray, Size(3.0, 3.0), 0.0)

            val w = gray.cols()
            val h = gray.rows()
            gx = Mat()
            gy = Mat()
            Imgproc.Sobel(gray, gx, CvType.CV_32F, 1, 0, 3)
            Imgproc.Sobel(gray, gy, CvType.CV_32F, 0, 1, 3)
            gray.release()
            gray = null

            val gxArr = FloatArray(w * h)
            val gyArr = FloatArray(w * h)
            gx.get(0, 0, gxArr)
            gy.get(0, 0, gyArr)
            gx.release()
            gx = null
            gy.release()
            gy = null

            val q = Array(4) { i -> doubleArrayOf(quadTLTRBRBL[i][0] * scale, quadTLTRBRBL[i][1] * scale) }

            val diag = hypot(w.toDouble(), h.toDouble())
            val searchRadius = diag * SEARCH_RADIUS_FRACTION coerceIn 4.0..48.0
            val maxShift = diag * MAX_CORNER_SHIFT_FRACTION

            val lines = arrayOfNulls<DoubleArray>(4)
            for (i in 0..3) {
                val a = q[i]
                val b = q[(i + 1) % 4]
                val fitted = snapEdgeLine(gxArr, gyArr, w, h, a, b, searchRadius)
                if (fitted != null) {
                    lines[i] = fitted
                } else {
                    val ex = b[0] - a[0]
                    val ey = b[1] - a[1]
                    val len = hypot(ex, ey)
                    if (len < 1e-6) return quadTLTRBRBL
                    lines[i] = doubleArrayOf(a[0], a[1], ex / len, ey / len)
                }
            }

            val refined = Array(4) { DoubleArray(2) }
            for (i in 0..3) {
                val p = intersectLines(lines[(i + 3) % 4]!!, lines[i]!!)
                val shift = if (p != null) hypot(p[0] - q[i][0], p[1] - q[i][1]) else Double.NaN
                if (p == null || shift > maxShift) {
                    refined[i][0] = q[i][0]
                    refined[i][1] = q[i][1]
                } else {
                    refined[i][0] = p[0]
                    refined[i][1] = p[1]
                }
            }

            if (!DocQuadDetector.isConvexTLTRBRBL(refined)) return quadTLTRBRBL

            val inv = 1.0 / scale
            return Array(4) { i -> doubleArrayOf(refined[i][0] * inv, refined[i][1] * inv) }
        } catch (t: Throwable) {
            return quadTLTRBRBL
        } finally {
            releaseQuietly(rgba)
            releaseQuietly(gray)
            releaseQuietly(gx)
            releaseQuietly(gy)
        }
    }

    private fun snapEdgeLine(
        gxArr: FloatArray,
        gyArr: FloatArray,
        w: Int,
        h: Int,
        a: DoubleArray,
        b: DoubleArray,
        searchRadius: Double
    ): DoubleArray? {
        val ex = b[0] - a[0]
        val ey = b[1] - a[1]
        val len = hypot(ex, ey)
        if (len < 8.0) return null
        val dx = ex / len
        val dy = ey / len
        val nx = -dy
        val ny = dx

        val r = ceil(searchRadius).toInt()
        val xs = DoubleArray(SAMPLES_PER_EDGE)
        val ys = DoubleArray(SAMPLES_PER_EDGE)
        var count = 0

        for (s in 0 until SAMPLES_PER_EDGE) {
            val t = EDGE_END_MARGIN + (1.0 - 2.0 * EDGE_END_MARGIN) * s / (SAMPLES_PER_EDGE - 1).toDouble()
            val px = a[0] + t * ex
            val py = a[1] + t * ey

            val n = 2 * r + 1
            val prof = DoubleArray(n)
            var maxResp = -1.0
            for (u in -r..r) {
                val ix = Math.round(px + u * nx).toInt()
                val iy = Math.round(py + u * ny).toInt()
                val resp: Double
                if (ix < 1 || iy < 1 || ix >= w - 1 || iy >= h - 1) {
                    resp = -1.0
                } else {
                    val idx = iy * w + ix
                    resp = Math.abs(gxArr[idx] * nx + gyArr[idx] * ny)
                }
                prof[u + r] = resp
                if (resp > maxResp) maxResp = resp
            }

            if (maxResp < MIN_GRADIENT_MAGNITUDE) continue

            val acceptThr = max(MIN_GRADIENT_MAGNITUDE, NEAREST_PEAK_ACCEPT_FRACTION * maxResp)
            var bestU = Int.MIN_VALUE
            var bestDist = Int.MAX_VALUE
            for (k in 1 until n - 1) {
                val c = prof[k]
                if (c < acceptThr) continue
                if (c < prof[k - 1] || c < prof[k + 1]) continue
                val u = k - r
                val dist = Math.abs(u)
                if (dist < bestDist) {
                    bestDist = dist
                    bestU = u
                }
            }
            if (bestU == Int.MIN_VALUE) continue

            var uRefined = bestU.toDouble()
            val k = bestU + r
            val respAtBestMinus1 = prof[k - 1]
            val bestResp = prof[k]
            val respAtBestPlus1 = prof[k + 1]
            if (respAtBestMinus1 >= 0 && respAtBestPlus1 >= 0) {
                val denom = respAtBestMinus1 - 2.0 * bestResp + respAtBestPlus1
                if (Math.abs(denom) > 1e-9) {
                    val offset = 0.5 * (respAtBestMinus1 - respAtBestPlus1) / denom
                    if (offset >= -1.0 && offset <= 1.0) uRefined = bestU + offset
                }
            }

            xs[count] = px + uRefined * nx
            ys[count] = py + uRefined * ny
            count++
        }

        if (count < ceil(MIN_VALID_FRACTION * SAMPLES_PER_EDGE)) return null

        var line = fitLineTLS(xs, ys, count) ?: return null

        val res = DoubleArray(count)
        for (i in 0 until count) res[i] = Math.abs(pointLineDistance(line, xs[i], ys[i]))
        val medAbs = median(res, count)
        if (medAbs > MAX_MEDIAN_RESIDUAL) return null
        val thr = max(1.5, 3.0 * medAbs)
        val xs2 = DoubleArray(count)
        val ys2 = DoubleArray(count)
        var kept = 0
        for (i in 0 until count) {
            if (res[i] <= thr) {
                xs2[kept] = xs[i]
                ys2[kept] = ys[i]
                kept++
            }
        }
        if (kept < ceil(MIN_VALID_FRACTION * SAMPLES_PER_EDGE)) return null
        return fitLineTLS(xs2, ys2, kept) ?: line
    }

    /** TLS (PCA principal-axis) line fit → {px, py, dx, dy}. */
    private fun fitLineTLS(xs: DoubleArray, ys: DoubleArray, n: Int): DoubleArray? {
        if (n < 2) return null
        var mx = 0.0
        var my = 0.0
        for (i in 0 until n) {
            mx += xs[i]
            my += ys[i]
        }
        mx /= n
        my /= n
        var sxx = 0.0
        var sxy = 0.0
        var syy = 0.0
        for (i in 0 until n) {
            val cx = xs[i] - mx
            val cy = ys[i] - my
            sxx += cx * cx
            sxy += cx * cy
            syy += cy * cy
        }
        if (sxx + syy < 1e-12) return null
        val theta = 0.5 * Math.atan2(2.0 * sxy, sxx - syy)
        return doubleArrayOf(mx, my, Math.cos(theta), Math.sin(theta))
    }

    private fun intersectLines(l1: DoubleArray, l2: DoubleArray): DoubleArray? {
        val cross = l1[2] * l2[3] - l1[3] * l2[2]
        if (Math.abs(cross) < 1e-9) return null
        val qpx = l2[0] - l1[0]
        val qpy = l2[1] - l1[1]
        val t = (qpx * l2[3] - qpy * l2[2]) / cross
        return doubleArrayOf(l1[0] + t * l1[2], l1[1] + t * l1[3])
    }

    private fun pointLineDistance(line: DoubleArray, x: Double, y: Double): Double {
        val rx = x - line[0]
        val ry = y - line[1]
        return rx * -line[3] + ry * line[2]
    }

    private fun median(values: DoubleArray, n: Int): Double {
        val copy = values.copyOf(n)
        copy.sort()
        return if (n % 2 == 1) copy[n / 2] else 0.5 * (copy[n / 2 - 1] + copy[n / 2])
    }

    private fun isPlausibleQuad(q: Array<DoubleArray>): Boolean {
        if (q.size != 4) return false
        for (p in q) {
            if (p.size != 2) return false
            if (p[0].isNaN() || p[1].isNaN()) return false
            if (p[0].isInfinite() || p[1].isInfinite()) return false
        }
        return true
    }

    private fun releaseQuietly(m: Mat?) {
        try {
            m?.release()
        } catch (_: Throwable) {
        }
    }
}
