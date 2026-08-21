package com.docuscan.app.scan

import android.graphics.Bitmap
import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Detect the four corners of a document page inside a photo.
 *
 * Pipeline (validated in a JS prototype against synthetic scenes: page on a
 * bedsheet / desk / warm / text / vignette / shadow / wrinkle / rotated 0.5-0.75rad /
 * plain / near-full-frame / and a no-page photo which must return null):
 *   downscale -> grayscale -> 3x3 box blur -> Sobel^2 edges -> several thresholds ->
 *   binary + dilate -> 8-connected components (skip ones touching the image border) ->
 *   convex hull -> Douglas-Peucker -> reduce to quad -> order TL,TR,BR,BL ->
 *   validate (convex, area range, border margin, corner cosine) -> corner refinement ->
 *   score = area * sideSupport + WEIGHT*(1-maxCornerCos) -> best across thresholds.
 *
 * Returns null when no convincing page quad is found (e.g. a plain photo).
 */
object AutoCrop {

    private const val MAX_DIM = 640
    private const val WEIGHT = 1_000_000.0
    private const val MAX_CORNER_COS = 0.65f
    private const val MIN_SUPPORT = 0.3f

    private data class Pt(val x: Float, val y: Float)

    private data class Quad(val quad: List<Pt>, val score: Double)

    private data class Comp(val id: Int, val count: Int, val pts: IntArray, val lab: IntArray)

    private class Validation(val ok: Boolean, val area: Float = 0f, val maxCos: Float = 0f)

    private val DX = intArrayOf(1, -1, 0, 0)
    private val DY = intArrayOf(0, 0, 1, -1)

    /** Detect page corners in [src] image space. Result order: TL, TR, BR, BL. */
    fun detectCorners(src: Bitmap): List<PointF>? {
        val w0 = src.width
        val h0 = src.height
        if (w0 < 24 || h0 < 24) return null
        val scale = min(1f, MAX_DIM.toFloat() / max(w0, h0))
        var sW = w0
        var sH = h0
        var small = src
        if (scale < 1f) {
            sW = max(1, round(w0 * scale).toInt())
            sH = max(1, round(h0 * scale).toInt())
            small = Bitmap.createScaledBitmap(src, sW, sH, true)
        }

        val px = IntArray(sW * sH)
        small.getPixels(px, 0, sW, 0, 0, sW, sH)
        if (small !== src) small.recycle()

        val gray = grayscale(px)
        val blur = boxBlur(gray, sW, sH)
        val sob = sobelSq(blur, sW, sH)

        var sum = 0.0
        var sumSq = 0.0
        var maxV = 0.0
        for (i in sob.indices) {
            val v = sob[i].toDouble()
            sum += v
            sumSq += v * v
            if (v > maxV) maxV = v
        }
        val n = sob.size
        val mean = sum / n
        val std = sqrt(sumSq / n - mean * mean)
        val thresholds = doubleArrayOf(
            mean + 0.5 * std, mean + 1.0 * std, mean + 1.5 * std, mean + 2.0 * std,
            maxV * 0.12, maxV * 0.2, maxV * 0.32
        )

        var best: Quad? = null
        for (th in thresholds) {
            val mask = thresholdDilate(sob, sW, sH, th.toFloat())
            val comps = components(mask, sW, sH)
            for (comp in comps) {
                if (comp.count < 200) continue
                var touches = false
                for (i in comp.pts) {
                    val x = i % sW
                    val y = i / sW
                    if (x <= 2 || y <= 2 || x >= sW - 3 || y >= sH - 3) {
                        touches = true
                        break
                    }
                }
                if (touches) continue

                var bp = boundaryPts(comp.lab, comp.id, sW, sH, comp.pts)
                if (bp.size > 4000) {
                    val step = ceil(bp.size.toFloat() / 4000).toInt()
                    bp = bp.filterIndexed { i, _ -> i % step == 0 }
                }
                val hull = convexHull(bp)
                if (hull.size < 3) continue
                var poly = simplifyRing(hull, 0.02 * 2 * (sW + sH))
                if (poly.size > 6) poly = simplifyRing(hull, 0.01 * 2 * (sW + sH))
                if (poly.size < 4) continue
                poly = reduceToQuad(poly)
                if (poly.size != 4) continue
                poly = orderCorners(poly)

                val v = validate(poly, sW, sH)
                if (!v.ok) continue
                val refined = refineCorner(poly, bp, sW, sH)
                val v2 = validate(refined, sW, sH)
                val final = if (v2.ok) refineCorner(refined, bp, sW, sH) else refined
                val v3 = validate(final, sW, sH)
                val quad = if (v3.ok) final else if (v2.ok) refined else poly

                val area = shoelace(quad)
                val maxCos = maxCornerCos(quad)
                val support = sideSupport(quad, bp)
                if (support < MIN_SUPPORT) continue
                val score = area.toDouble() * support + WEIGHT * (1.0 - maxCos.toDouble())
                if (best == null || score > best.score) {
                    best = Quad(quad, score)
                }
            }
        }

        if (best == null) return null
        val quad = best.quad.map { p ->
            Pt(
                max(0f, min((w0 - 1).toFloat(), p.x / scale)),
                max(0f, min((h0 - 1).toFloat(), p.y / scale))
            )
        }
        return orderCorners(quad).map { PointF(it.x, it.y) }
    }

    private fun grayscale(px: IntArray): IntArray {
        val g = IntArray(px.size)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 255
            val gr = (c shr 8) and 255
            val b = c and 255
            g[i] = (r * 299 + gr * 587 + b * 114 + 500) / 1000
        }
        return g
    }

    private fun boxBlur(g: IntArray, w: Int, h: Int): IntArray {
        val tmp = IntArray(g.size)
        val out = IntArray(g.size)
        for (y in 0 until h) {
            var i = y * w
            for (x in 0 until w) {
                var s = 0
                for (dx in -1..1) {
                    val xx = max(0, min(w - 1, x + dx))
                    s += g[y * w + xx]
                }
                tmp[i] = s / 3
                i++
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0
                for (dy in -1..1) {
                    val yy = max(0, min(h - 1, y + dy))
                    s += tmp[yy * w + x]
                }
                out[y * w + x] = s / 3
            }
        }
        return out
    }

    private fun sobelSq(g: IntArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val tl = g[i - w - 1].toFloat(); val tc = g[i - w].toFloat(); val tr = g[i - w + 1].toFloat()
                val l = g[i - 1].toFloat(); val r = g[i + 1].toFloat()
                val bl = g[i + w - 1].toFloat(); val bc = g[i + w].toFloat(); val br = g[i + w + 1].toFloat()
                val gx = -tl - 2 * l - bl + tr + 2 * r + br
                val gy = -tl - 2 * tc - tr + bl + 2 * bc + br
                out[i] = gx * gx + gy * gy
            }
        }
        return out
    }

    private fun thresholdDilate(sob: FloatArray, w: Int, h: Int, th: Float): IntArray {
        val bin = IntArray(w * h)
        for (i in bin.indices) bin[i] = if (sob[i] > th) 1 else 0
        val tmp = IntArray(w * h)
        for (y in 0 until h) {
            var i = y * w
            for (x in 0 until w) {
                var m = bin[i]
                for (dx in -1..1) {
                    val xx = max(0, min(w - 1, x + dx))
                    if (bin[y * w + xx] != 0) { m = 1; break }
                }
                tmp[i] = m
                i++
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var m = tmp[y * w + x]
                for (dy in -1..1) {
                    val yy = max(0, min(h - 1, y + dy))
                    if (tmp[yy * w + x] != 0) { m = 1; break }
                }
                bin[y * w + x] = m
            }
        }
        return bin
    }

    private fun components(mask: IntArray, w: Int, h: Int): List<Comp> {
        val lab = IntArray(w * h)
        val counts = ArrayList<Int>()
        val pixels = ArrayList<IntArray>()
        val stack = ArrayDeque<Int>()
        var nid = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val idx = y * w + x
                if (mask[idx] != 0 && lab[idx] == 0) {
                    nid++
                    var cnt = 0
                    stack.clear()
                    stack.addLast(idx)
                    lab[idx] = nid
                    val pts = ArrayList<Int>()
                    while (stack.isNotEmpty()) {
                        val i = stack.removeLast()
                        cnt++
                        pts.add(i)
                        val xi = i % w
                        val yi = i / w
                        for (dy in -1..1) for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = xi + dx
                            val ny = yi + dy
                            if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                            val j = ny * w + nx
                            if (mask[j] != 0 && lab[j] == 0) { lab[j] = nid; stack.addLast(j) }
                        }
                    }
                    counts.add(cnt)
                    pixels.add(pts.toIntArray())
                }
            }
        }
        val order = counts.indices.sortedByDescending { counts[it] }.take(6)
        return order.map { Comp(it + 1, counts[it], pixels[it], lab) }
    }

    private fun boundaryPts(lab: IntArray, id: Int, w: Int, h: Int, pts: IntArray): List<Pt> {
        val out = ArrayList<Pt>(pts.size)
        for (i in pts) {
            val x = i % w
            val y = i / w
            var on = false
            for (d in 0 until 4) {
                val nx = x + DX[d]
                val ny = y + DY[d]
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) { on = true; break }
                if (lab[ny * w + nx] != id) { on = true; break }
            }
            if (on) out.add(Pt(x.toFloat(), y.toFloat()))
        }
        return out
    }

    private fun convexHull(ptsIn: List<Pt>): List<Pt> {
        val pts = ptsIn.sortedWith(compareBy({ it.x }, { it.y }))
        if (pts.size < 3) return pts
        val cross = { o: Pt, a: Pt, b: Pt -> (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x) }
        val lower = ArrayDeque<Pt>()
        for (p in pts) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0f) lower.removeLast()
            lower.addLast(p)
        }
        val upper = ArrayDeque<Pt>()
        for (i in pts.indices.reversed()) {
            val p = pts[i]
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0f) upper.removeLast()
            upper.addLast(p)
        }
        lower.removeLast()
        upper.removeLast()
        return lower + upper
    }

    private fun simplifyOpen(pts: List<Pt>, eps: Double): List<Pt> {
        if (pts.size < 3) return pts
        val keep = BooleanArray(pts.size)
        keep[0] = true
        keep[pts.size - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, pts.size - 1))
        while (stack.isNotEmpty()) {
            val seg = stack.removeLast()
            val s = seg[0]
            val e = seg[1]
            if (e - s <= 1) continue
            var maxD = -1.0
            var idx = -1
            for (i in s + 1 until e) {
                val d = distToSegment(pts[i], pts[s], pts[e])
                if (d > maxD) { maxD = d; idx = i }
            }
            if (maxD <= eps || idx < 0) continue
            keep[idx] = true
            stack.addLast(intArrayOf(s, idx))
            stack.addLast(intArrayOf(idx, e))
        }
        return pts.filterIndexed { i, _ -> keep[i] }
    }

    private fun simplifyRing(pts: List<Pt>, eps: Double): List<Pt> {
        if (pts.size <= 8) return pts
        var bi = intArrayOf(0, 0)
        var bd = -1.0
        for (i in pts.indices) for (j in i + 1 until pts.size) {
            val dx = pts[i].x - pts[j].x
            val dy = pts[i].y - pts[j].y
            val d = dx * dx + dy * dy
            if (d > bd) { bd = d; bi = intArrayOf(i, j) }
        }
        val a = bi[0]
        val b = bi[1]
        val c1 = ArrayList<Pt>()
        val c2 = ArrayList<Pt>()
        var k = a
        while (true) { c1.add(pts[k]); if (k == b) break; k = (k + 1) % pts.size }
        k = a
        while (true) { c2.add(pts[k]); if (k == b) break; k = (k - 1 + pts.size) % pts.size }
        val r1 = simplifyOpen(c1, eps)
        val r2 = simplifyOpen(c2, eps)
        val tail = if (r2.size > 1) r2.subList(1, r2.size - 1) else emptyList()
        return r1 + tail
    }

    private fun reduceToQuad(polyIn: List<Pt>): List<Pt> {
        val poly = polyIn.toMutableList()
        while (poly.size > 4) {
            var best = -1
            var bestArea = Float.MAX_VALUE
            for (i in poly.indices) {
                val a = poly[(i - 1 + poly.size) % poly.size]
                val b = poly[i]
                val c = poly[(i + 1) % poly.size]
                val ar = abs(triArea(a, b, c))
                if (ar < bestArea) { bestArea = ar; best = i }
            }
            if (best < 0) break
            poly.removeAt(best)
        }
        return poly
    }

    private fun orderCorners(pts: List<Pt>): List<Pt> {
        if (pts.size != 4) return pts
        val cx = pts.sumOf { it.x } / 4f
        val cy = pts.sumOf { it.y } / 4f
        val ang = pts.map { atan2(it.y - cy, it.x - cx) }
        val idx = (0..3).sortedBy { ang[it] }
        val quad = idx.map { pts[it] }
        val sums = pts.map { it.x + it.y }
        val difs = pts.map { it.x - it.y }
        val minS = sums.minOrNull()!!
        val maxS = sums.maxOrNull()!!
        val minD = difs.minOrNull()!!
        val maxD = difs.maxOrNull()!!
        var best = quad
        var bestScore = -1e9
        for (r in 0 until 4) {
            for (dir in intArrayOf(1, -1)) {
                val cand = List(4) { k -> quad[((r + dir * k) % 4 + 4) % 4] }
                var sc = 0.0
                for (k in 0 until 4) {
                    val pos = pts.indexOf(cand[k])
                    val s = sums[pos]
                    val d = difs[pos]
                    if (k == 0 && abs(s - minS) < 1e-6f) sc += 4.0
                    if (k == 2 && abs(s - maxS) < 1e-6f) sc += 4.0
                    if (k == 1 && abs(d - maxD) < 1e-6f) sc += 2.0
                    if (k == 3 && abs(d - minD) < 1e-6f) sc += 2.0
                }
                if (sc > bestScore) { bestScore = sc; best = cand }
            }
        }
        return best
    }

    private fun triArea(a: Pt, b: Pt, c: Pt): Float =
        (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)

    private fun distToSegment(p: Pt, a: Pt, b: Pt): Double {
        val abx = b.x - a.x
        val aby = b.y - a.y
        val len2 = abx * abx + aby * aby
        var t = if (len2 > 0f) ((p.x - a.x) * abx + (p.y - a.y) * aby) / len2 else 0f
        t = max(0f, min(1f, t))
        val qx = a.x + t * abx
        val qy = a.y + t * aby
        return hypot((p.x - qx).toDouble(), (p.y - qy).toDouble())
    }

    private fun shoelace(quad: List<Pt>): Float {
        var s = 0f
        for (i in 0 until 4) {
            val a = quad[i]
            val b = quad[(i + 1) % 4]
            s += a.x * b.y - b.x * a.y
        }
        return abs(s) / 2f
    }

    private fun maxCornerCos(quad: List<Pt>): Float {
        var mc = -Float.MAX_VALUE
        for (i in 0 until 4) {
            val p = quad[i]
            val v1x = quad[(i + 3) % 4].x - p.x
            val v1y = quad[(i + 3) % 4].y - p.y
            val v2x = quad[(i + 1) % 4].x - p.x
            val v2y = quad[(i + 1) % 4].y - p.y
            val l1 = hypot(v1x.toDouble(), v1y.toDouble()).toFloat()
            val l2 = hypot(v2x.toDouble(), v2y.toDouble()).toFloat()
            if (l1 < 1e-4f || l2 < 1e-4f) continue
            val c = (v1x * v2x + v1y * v2y) / (l1 * l2)
            if (c > mc) mc = c
        }
        return mc
    }

    private fun validate(quad: List<Pt>, sW: Int, sH: Int): Validation {
        val cross = FloatArray(4)
        for (i in 0 until 4) {
            val a = quad[i]
            val b = quad[(i + 1) % 4]
            val c = quad[(i + 2) % 4]
            cross[i] = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        }
        val pos = cross.all { it > 0f }
        val neg = cross.all { it < 0f }
        if (!pos && !neg) return Validation(false)
        val area = shoelace(quad)
        if (area < 0.02f * sW * sH || area > 0.92f * sW * sH) return Validation(false)
        val m = 0.012f * min(sW, sH)
        for (p in quad) if (p.x < m || p.y < m || p.x > sW - m || p.y > sH - m) return Validation(false)
        val maxCos = maxCornerCos(quad)
        if (maxCos > MAX_CORNER_COS) return Validation(false)
        return Validation(true, area, maxCos)
    }

    private fun refineCorner(quadIn: List<Pt>, boundaryPts: List<Pt>, sW: Int, sH: Int): List<Pt> {
        val r = 0.10f * min(sW, sH)
        val r2 = r * r
        val out = ArrayList<Pt>(4)
        for (i in 0 until 4) {
            val prev = quadIn[(i + 3) % 4]
            val next = quadIn[(i + 1) % 4]
            val cur = quadIn[i]
            var bestPt = cur
            var bestD = -1.0
            for (p in boundaryPts) {
                val dx = p.x - cur.x
                val dy = p.y - cur.y
                if (dx * dx + dy * dy > r2) continue
                val d = distToSegment(p, prev, next)
                if (d > bestD) { bestD = d; bestPt = p }
            }
            out.add(bestPt)
        }
        return out
    }

    private fun sideSupport(quad: List<Pt>, bp: List<Pt>, tol: Float = 2.5f): Float {
        var total = 0f
        for (i in 0 until 4) {
            val a = quad[i]
            val b = quad[(i + 1) % 4]
            val len = hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()).toFloat()
            if (len < 1f) continue
            var c = 0
            for (p in bp) if (distToSegment(p, a, b) <= tol.toDouble()) c++
            total += min(1f, c / len)
        }
        return total / 4f
    }
}
