package com.docuscan.app.scan

import android.graphics.Bitmap
import android.graphics.PointF
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.CLAHE
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/**
 * OpenCV-based document cleanup filters and corner detection, ported from
 * MakeACopy (Apache 2.0) so the "Natural / Enhanced / Clean Text" presets and
 * the auto-crop behavior match exactly. Runs fully offline on-device.
 */
object Cleanup {

    const val KERNEL_FRACTION_BW = 0.08
    const val KERNEL_FRACTION_OCR = 0.03
    private const val DETECTION_MAX_EDGE = 720

    @Volatile
    private var loaded = false

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        synchronized(this) {
            if (loaded) return true
            return try {
                System.loadLibrary("opencv_java4")
                runCatching { Core.setNumThreads(2) }
                loaded = true
                true
            } catch (e: Throwable) {
                false
            }
        }
    }

    /**
     * Applies a MakeACopy document-cleanup preset.
     * [mode] is one of "natural", "enhanced", "cleantext".
     * Returns a new bitmap, or [src] unchanged on failure.
     */
    fun apply(src: Bitmap, mode: String): Bitmap {
        if (!ensureLoaded()) return src
        return try {
            when (mode) {
                "natural" -> applyNatural(src)
                "enhanced" -> applyHighPassColor(src, true) ?: src
                "cleantext" -> applyCleanTextColor(src) ?: src
                else -> src
            }
        } catch (e: Throwable) {
            src
        }
    }

    // ---------------------------------------------------------------- cleanup

    private fun applyNatural(src: Bitmap): Bitmap {
        val rgba = Mat()
        val bgr = Mat()
        val lab = Mat()
        try {
            Utils.bitmapToMat(src, rgba)
            Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
            Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)
            val channels = mutableListOf<Mat>()
            Core.split(lab, channels)
            val clahe: CLAHE = Imgproc.createCLAHE(1.4, Size(8.0, 8.0))
            clahe.apply(channels[0], channels[0])
            clahe.collectGarbage()
            Core.merge(channels, lab)
            channels.forEach { it.release() }
            Imgproc.cvtColor(lab, bgr, Imgproc.COLOR_Lab2BGR)
            sharpen(bgr, 1.15)
            Imgproc.cvtColor(bgr, rgba, Imgproc.COLOR_BGR2RGBA)
            return matToBitmap(rgba) ?: src
        } finally {
            rgba.release(); bgr.release(); lab.release()
        }
    }

    private fun applyHighPassColor(src: Bitmap, applyClahe: Boolean): Bitmap? {
        val rgba = Mat()
        val rgb = Mat()
        val lab = Mat()
        val labOut = Mat()
        val rgbOut = Mat()
        val rgbaOut = Mat()
        try {
            Utils.bitmapToMat(src, rgba)
            Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(rgb, lab, Imgproc.COLOR_RGB2Lab)
            if (applyClahe) {
                val channels = mutableListOf<Mat>()
                Core.split(lab, channels)
                val lNorm = Mat()
                backgroundDivideGray(channels[0], lNorm, KERNEL_FRACTION_BW)
                val clahe: CLAHE = Imgproc.createCLAHE(1.5, Size(8.0, 8.0))
                clahe.apply(lNorm, lNorm)
                clahe.collectGarbage()
                channels[0].release()
                channels[0] = lNorm
                Core.merge(channels, labOut)
                channels.forEach { c -> if (c !== lNorm) c.release() }
                lNorm.release()
            } else {
                backgroundDivideLab(lab, labOut, KERNEL_FRACTION_BW)
            }
            Imgproc.cvtColor(labOut, rgbOut, Imgproc.COLOR_Lab2RGB)
            Imgproc.cvtColor(rgbOut, rgbaOut, Imgproc.COLOR_RGB2RGBA)
            return matToBitmap(rgbaOut)
        } finally {
            rgba.release(); rgb.release(); lab.release(); labOut.release()
            rgbOut.release(); rgbaOut.release()
        }
    }

    private fun applyCleanTextColor(src: Bitmap): Bitmap? {
        val luminance = prepareForOcr(src) ?: return null
        val colorRgba = Mat()
        val colorBgr = Mat()
        val lab = Mat()
        val cleanRgba = Mat()
        val cleanGray = Mat()
        val outRgba = Mat()
        try {
            Utils.bitmapToMat(src, colorRgba)
            Utils.bitmapToMat(luminance, cleanRgba)
            Imgproc.cvtColor(colorRgba, colorBgr, Imgproc.COLOR_RGBA2BGR)
            Imgproc.cvtColor(colorBgr, lab, Imgproc.COLOR_BGR2Lab)
            Imgproc.cvtColor(cleanRgba, cleanGray, Imgproc.COLOR_RGBA2GRAY)
            val channels = mutableListOf<Mat>()
            Core.split(lab, channels)
            cleanGray.copyTo(channels[0])
            Core.merge(channels, lab)
            channels.forEach { it.release() }
            Imgproc.cvtColor(lab, colorBgr, Imgproc.COLOR_Lab2BGR)
            Imgproc.cvtColor(colorBgr, outRgba, Imgproc.COLOR_BGR2RGBA)
            return matToBitmap(outRgba)
        } finally {
            if (luminance !== src) runCatching { luminance.recycle() }
            colorRgba.release(); colorBgr.release(); lab.release()
            cleanRgba.release(); cleanGray.release(); outRgba.release()
        }
    }

    /** Grayscale OCR-preprocessing path of MakeACopy's prepareForOCR(binaryOutput=false). */
    fun prepareForOcr(src: Bitmap): Bitmap? {
        val rgba = Mat()
        val gray = Mat()
        val work = Mat()
        try {
            Utils.bitmapToMat(src, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val mean = Core.mean(gray).`val`[0]
            if (mean < 128.0) {
                Core.bitwise_not(gray, gray)
            }
            backgroundDivideGray(gray, work, KERNEL_FRACTION_OCR)
            Imgproc.medianBlur(work, work, 3)
            val clahe: CLAHE = Imgproc.createCLAHE(1.2, Size(8.0, 8.0))
            clahe.apply(work, work)
            clahe.collectGarbage()
            val blurred = Mat()
            Imgproc.GaussianBlur(work, blurred, Size(0.0, 0.0), 1.0)
            Core.addWeighted(work, 1.5, blurred, -0.5, 0.0, work)
            blurred.release()
            ensureMinTextScale(work, 1800.0, 2.0)
            if (work.cols() != src.width || work.rows() != src.height) {
                val resized = Mat()
                Imgproc.resize(work, resized, Size(src.width.toDouble(), src.height.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
                work.release()
                return matToBitmap(resized)
            }
            return matToBitmap(work)
        } finally {
            rgba.release(); gray.release(); work.release()
        }
    }

    private fun backgroundDivideGray(gray: Mat, dst: Mat, kernelFraction: Double, minKernel: Int = 15) {
        var k = max(max(3, minKernel), (min(gray.width(), gray.height()) * kernelFraction).toInt())
        if (k % 2 == 0) k++
        val bg = Mat()
        val gf = Mat()
        val bgf = Mat()
        val norm = Mat()
        try {
            Imgproc.GaussianBlur(gray, bg, Size(k.toDouble(), k.toDouble()), 0.0)
            gray.convertTo(gf, CvType.CV_32F)
            bg.convertTo(bgf, CvType.CV_32F)
            Core.max(bgf, Scalar(1.0), bgf)
            Core.divide(gf, bgf, norm)
            Core.multiply(norm, Scalar(255.0), norm)
            norm.convertTo(dst, CvType.CV_8U)
        } finally {
            bg.release(); gf.release(); bgf.release(); norm.release()
        }
    }

    private fun backgroundDivideLab(lab: Mat, dst: Mat, kernelFraction: Double) {
        val channels = mutableListOf<Mat>()
        val lNorm = Mat()
        try {
            Core.split(lab, channels)
            if (channels.size < 3) {
                lab.copyTo(dst)
                return
            }
            backgroundDivideGray(channels[0], lNorm, kernelFraction)
            channels[0].release()
            channels[0] = lNorm
            Core.merge(channels, dst)
        } finally {
            channels.forEach { c -> if (c !== lNorm) c.release() }
            lNorm.release()
        }
    }

    private fun sharpen(mat: Mat, amount: Double) {
        val blurred = Mat()
        try {
            Imgproc.GaussianBlur(mat, blurred, Size(0.0, 0.0), 1.0)
            Core.addWeighted(mat, amount, blurred, 1.0 - amount, 0.0, mat)
        } finally {
            blurred.release()
        }
    }

    private fun ensureMinTextScale(singleChannel: Mat, minLongSide: Double, scaleMax: Double) {
        val w = singleChannel.cols()
        val h = singleChannel.rows()
        val longSide = max(w, h).toDouble()
        if (longSide >= minLongSide) return
        val scale = min(scaleMax, minLongSide / longSide)
        val nw = max(1, (w * scale).toInt())
        val nh = max(1, (h * scale).toInt())
        val scaled = Mat()
        Imgproc.resize(singleChannel, scaled, Size(nw.toDouble(), nh.toDouble()), 0.0, 0.0, Imgproc.INTER_CUBIC)
        scaled.copyTo(singleChannel)
        scaled.release()
    }

    private fun matToBitmap(m: Mat): Bitmap? {
        return try {
            val out = Bitmap.createBitmap(m.cols(), m.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(m, out)
            out
        } catch (e: Throwable) {
            null
        }
    }

    // ------------------------------------------------------- corner detection

    /**
     * Detects the four document corners using the MakeACopy OpenCV pipeline
     * (adaptive edges, contours, Hough fallback). Returns corners in the
     * original bitmap's coordinate space as [TL, TR, BR, BL], or null.
     */
    fun detectCorners(src: Bitmap): List<PointF>? {
        if (!ensureLoaded()) return null
        val w0 = src.width
        val h0 = src.height
        val scaled = BitmapUtil.fitMax(src, DETECTION_MAX_EDGE)
        val pts = detectCornersDetailed(scaled) ?: run {
            if (scaled !== src) scaled.recycle()
            return null
        }
        val sx = w0.toFloat() / scaled.width
        val sy = h0.toFloat() / scaled.height
        if (scaled !== src) scaled.recycle()
        return pts.map { PointF(it.x * sx, it.y * sy) }
    }

    private fun detectCornersDetailed(bitmap: Bitmap): List<PointF>? {
        val rgba = Mat()
        val gray = Mat()
        val threshold = Mat()
        val morph = Mat()
        val edges = Mat()
        val edgesCopy = Mat()
        val hierarchy = Mat()
        val contours = mutableListOf<MatOfPoint>()
        try {
            Utils.bitmapToMat(bitmap, rgba)
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
            Imgproc.threshold(gray, threshold, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)

            val shortSide = min(rgba.width(), rgba.height())
            var kernelSize = max(5, shortSide / 50)
            if (kernelSize % 2 == 0) kernelSize++
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(kernelSize.toDouble(), kernelSize.toDouble()))
            Imgproc.morphologyEx(threshold, morph, Imgproc.MORPH_CLOSE, kernel)
            kernel.release()

            val median = Core.mean(gray).`val`[0]
            val cannyLower = max(0.0, 0.66 * median)
            val cannyUpper = min(255.0, 1.33 * median)
            Imgproc.Canny(morph, edges, cannyLower, cannyUpper)

            val edgesAuto = Mat()
            edgesAdaptive(gray, edgesAuto)
            Core.max(edges, edgesAuto, edges)
            edgesAuto.release()

            val low = isLowLight(rgba)
            if (low) {
                val ll = rgba.clone()
                preprocessLowLight(ll)
                val llGray = Mat()
                Imgproc.cvtColor(ll, llGray, Imgproc.COLOR_RGBA2GRAY)
                val edges2 = Mat()
                edgesAdaptive(llGray, edges2)
                val k3 = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
                Imgproc.dilate(edges2, edges2, k3)
                k3.release()
                Core.max(edges, edges2, edges)
                edges2.release(); llGray.release(); ll.release()
            }

            edges.copyTo(edgesCopy)
            Imgproc.findContours(edgesCopy, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            val imgArea = rgba.width() * rgba.height().toDouble()
            var bestScore = -1.0
            var bestQuad: List<PointF>? = null

            for (contour in contours) {
                val area = Imgproc.contourArea(contour)
                if (area < imgArea * 0.08) continue
                val curve = MatOfPoint2f(*contour.toArray())
                val approx = MatOfPoint2f()
                try {
                    Imgproc.approxPolyDP(curve, approx, Imgproc.arcLength(curve, true) * 0.015, true)
                    val approxAsPoints = MatOfPoint(*approx.toArray())
                    val isConvex = Imgproc.isContourConvex(approxAsPoints)
                    approxAsPoints.release()
                    if (approx.total() == 4 && isConvex) {
                        val quad = sortPointsRobust(approx.toArray())
                        val w1 = distance(quad[0], quad[1])
                        val w2 = distance(quad[2], quad[3])
                        val h1 = distance(quad[1], quad[2])
                        val h2 = distance(quad[3], quad[0])
                        val avgWidth = (w1 + w2) / 2.0
                        val avgHeight = (h1 + h2) / 2.0
                        val aspectRatio = avgHeight / (avgWidth + 1e-9)
                        val areaNorm = area / imgArea
                        val rectRaw = rectScore(quad)
                        if (rectRaw < 0.0) continue
                        val rect = rectRaw / 120.0
                        val score = 0.6 * areaNorm + 0.4 * rect
                        if (aspectRatio > 0.5 && aspectRatio < 2.5 && score > bestScore) {
                            bestScore = score
                            bestQuad = quad.map { PointF(it.x.toFloat(), it.y.toFloat()) }
                        }
                    }
                } finally {
                    curve.release(); approx.release()
                }
            }

            if (bestQuad != null) return bestQuad

            val houghQuad = detectQuadFromHoughLines(edges, rgba.width(), rgba.height())
            if (houghQuad != null) return houghQuad.map { PointF(it.x.toFloat(), it.y.toFloat()) }

            return null
        } finally {
            contours.forEach { runCatching { it.release() } }
            rgba.release(); gray.release(); threshold.release(); morph.release()
            edges.release(); edgesCopy.release(); hierarchy.release()
        }
    }

    private fun edgesAdaptive(srcGray: Mat, out: Mat) {
        val med = Mat()
        try {
            Imgproc.medianBlur(srcGray, med, 3)
            val v = Core.mean(med).`val`[0]
            val lower = max(0.0, (1.0 - 0.33) * v)
            val upper = min(255.0, (1.0 + 0.33) * v)
            Imgproc.Canny(med, out, lower, upper, 3, true)
        } finally {
            med.release()
        }
    }

    private fun detectQuadFromHoughLines(edges: Mat, imgW: Int, imgH: Int): Array<Point>? {
        val lines = Mat()
        try {
            val minLineLength = max(30, min(imgW, imgH) / 10)
            val threshold = max(50, minLineLength / 2)
            Imgproc.HoughLinesP(edges, lines, 1.0, Math.PI / 180.0, threshold, minLineLength, 10)
            if (lines.rows() < 4) return null

            val horizontal = mutableListOf<DoubleArray>()
            val vertical = mutableListOf<DoubleArray>()
            for (i in 0 until lines.rows()) {
                val line = lines.get(i, 0)
                val x1 = line[0]; val y1 = line[1]; val x2 = line[2]; val y2 = line[3]
                var angle = Math.toDegrees(atan2(y2 - y1, x2 - x1))
                angle = ((angle % 180) + 180) % 180
                when {
                    angle < 30 || angle > 150 -> horizontal.add(line)
                    angle > 60 && angle < 120 -> vertical.add(line)
                }
            }
            if (horizontal.size < 2 || vertical.size < 2) return null

            horizontal.sortBy { (it[1] + it[3]) / 2 }
            vertical.sortBy { (it[0] + it[2]) / 2 }
            val top = horizontal.first()
            val bottom = horizontal.last()
            val left = vertical.first()
            val right = vertical.last()

            val tl = lineIntersection(top, left) ?: return null
            val tr = lineIntersection(top, right) ?: return null
            val br = lineIntersection(bottom, right) ?: return null
            val bl = lineIntersection(bottom, left) ?: return null

            val quad = sortPointsRobust(
                arrayOf(
                    clampPoint(tl, imgW, imgH),
                    clampPoint(tr, imgW, imgH),
                    clampPoint(br, imgW, imgH),
                    clampPoint(bl, imgW, imgH)
                )
            )
            val area = quadArea(quad)
            val imgArea = imgW * imgH.toDouble()
            if (area < imgArea * 0.05) return null
            if (hasAcuteOrReflexAngles(quad)) return null
            return quad
        } finally {
            lines.release()
        }
    }

    private fun lineIntersection(line1: DoubleArray, line2: DoubleArray): Point? {
        val x1 = line1[0]; val y1 = line1[1]; val x2 = line1[2]; val y2 = line1[3]
        val x3 = line2[0]; val y3 = line2[1]; val x4 = line2[2]; val y4 = line2[3]
        val denom = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4)
        if (abs(denom) < 1e-10) return null
        val t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / denom
        return Point(x1 + t * (x2 - x1), y1 + t * (y2 - y1))
    }

    private fun clampPoint(p: Point, w: Int, h: Int): Point {
        return Point(p.x.coerceIn(0.0, w.toDouble()), p.y.coerceIn(0.0, h.toDouble()))
    }

    private fun sortPointsRobust(src: Array<Point>): Array<Point> {
        if (src.size != 4) return src
        val pts = src.toMutableList()
        val cx = pts.sumOf { it.x } / 4.0
        val cy = pts.sumOf { it.y } / 4.0
        pts.sortBy { atan2(it.y - cy, it.x - cx) }
        var start = 0
        var best = Double.MAX_VALUE
        for (i in 0 until 4) {
            val s = pts[i].x + pts[i].y
            if (s < best) {
                best = s
                start = i
            }
        }
        return Array(4) { i -> pts[(start + i) % 4] }
    }

    private fun rectScore(q: Array<Point>): Double {
        var score = 0.0
        for (i in 0 until 4) {
            val a = q[i]
            val prev = q[(i + 3) % 4]
            val next = q[(i + 1) % 4]
            val ang = angle(prev, a, next)
            if (ang.isNaN() || ang.isInfinite()) return -1.0
            if (ang < 60.0 || ang > 120.0) return -1.0
            val dev = abs(ang - 90.0)
            val perCorner = 30.0 - dev
            if (perCorner > 0) score += perCorner
        }
        return score
    }

    private fun angle(b: Point, a: Point, c: Point): Double {
        val abx = b.x - a.x; val aby = b.y - a.y
        val acx = c.x - a.x; val acy = c.y - a.y
        val num = abx * acx + aby * acy
        val den = Math.hypot(abx, aby) * Math.hypot(acx, acy) + 1e-9
        return Math.toDegrees(Math.acos(max(-1.0, min(1.0, num / den))))
    }

    private fun hasAcuteOrReflexAngles(q: Array<Point>): Boolean {
        val p = sortPointsRobust(q)
        for (i in 0 until 4) {
            val a = p[i]
            val prev = p[(i + 3) % 4]
            val next = p[(i + 1) % 4]
            val ang = angle(prev, a, next)
            if (ang.isNaN() || ang.isInfinite()) return true
            if (ang < 28.0 || ang > 152.0) return true
        }
        return false
    }

    private fun quadArea(q: Array<Point>): Double {
        var area = 0.0
        for (i in 0 until 4) {
            val a = q[i]
            val b = q[(i + 1) % 4]
            area += a.x * b.y - b.x * a.y
        }
        return abs(area) / 2.0
    }

    private fun distance(a: Point, b: Point): Double = Math.hypot(a.x - b.x, a.y - b.y)

    private fun isLowLight(rgba: Mat): Boolean {
        val gray = Mat()
        try {
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val total = gray.total().toInt()
            val buf = ByteArray(total)
            gray.get(0, 0, buf)
            val hist = IntArray(256)
            for (i in 0 until total) {
                hist[buf[i].toInt() and 0xFF]++
            }
            var cum = 0L
            val target = total.toLong() / 2
            for (i in 0 until 256) {
                cum += hist[i]
                if (cum >= target) return i < 60
            }
            return false
        } finally {
            gray.release()
        }
    }

    private fun preprocessLowLight(rgbaOrGray: Mat) {
        val gray = Mat()
        try {
            if (rgbaOrGray.channels() != 1) {
                Imgproc.cvtColor(rgbaOrGray, gray, Imgproc.COLOR_RGBA2GRAY)
            } else {
                gray.release()
                return
            }
            val f = Mat()
            gray.convertTo(f, CvType.CV_32F, 1.0 / 255.0)
            Core.pow(f, 0.75, f)
            Core.multiply(f, Scalar(255.0), f)
            f.convertTo(gray, CvType.CV_8U)
            f.release()
            val clahe: CLAHE = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
            clahe.apply(gray, gray)
            clahe.collectGarbage()
            val sharp = Mat()
            Imgproc.GaussianBlur(gray, sharp, Size(0.0, 0.0), 1.2)
            Core.addWeighted(gray, 1.6, sharp, -0.6, 0.0, gray)
            sharp.release()
            Imgproc.cvtColor(gray, rgbaOrGray, Imgproc.COLOR_GRAY2RGBA)
        } finally {
            gray.release()
        }
    }
}
