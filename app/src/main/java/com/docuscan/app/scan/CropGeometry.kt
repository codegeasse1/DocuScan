package com.docuscan.app.scan

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Pure-geometry helpers for crop-quadrilateral edge dragging and the
 * Snap-to-Right-Angle assist (ported from makeacopy's CropEdgeGeometry and
 * RightAngleSnap). Corners are kept in TL, TR, BR, BL cyclic order.
 */
object CropGeometry {

    const val EDGE_TOUCH_RADIUS_PX = 24f
    const val EDGE_END_DEADZONE = 0.15f
    const val IMG_OOB_TOL = 0.25f

    class Projection(val t: Float, val perpDist: Float)

    fun projectOntoSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Projection {
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        if (lenSq <= 1e-6f) {
            val ddx = px - ax
            val ddy = py - ay
            return Projection(0f, hypot(ddx, ddy))
        }
        val t = ((px - ax) * dx + (py - ay) * dy) / lenSq
        val tc = t.coerceIn(0f, 1f)
        val qx = ax + tc * dx
        val qy = ay + tc * dy
        return Projection(tc, hypot(px - qx, py - qy))
    }

    /** Edge index 0=Top, 1=Right, 2=Bottom, 3=Left, or -1. */
    fun findEdgeHit(xs: FloatArray, ys: FloatArray, x: Float, y: Float): Int {
        var best = -1
        var bestDist = EDGE_TOUCH_RADIUS_PX
        for (i in 0..3) {
            val j = (i + 1) % 4
            val p = projectOntoSegment(x, y, xs[i], ys[i], xs[j], ys[j])
            if (p.perpDist <= EDGE_TOUCH_RADIUS_PX &&
                p.t > EDGE_END_DEADZONE && p.t < 1f - EDGE_END_DEADZONE &&
                p.perpDist < bestDist
            ) {
                bestDist = p.perpDist
                best = i
            }
        }
        return best
    }

    class EdgeTranslation(
        val xs: FloatArray,
        val ys: FloatArray,
        val applied: Boolean,
        val dxOrth: Float,
        val dyOrth: Float
    )

    fun applyEdgeTranslation(
        xs0: FloatArray,
        ys0: FloatArray,
        edgeIndex: Int,
        m0x: Float,
        m0y: Float,
        nx: Float,
        ny: Float,
        px: Float,
        py: Float
    ): EdgeTranslation {
        val d = (px - m0x) * nx + (py - m0y) * ny
        val dx = d * nx
        val dy = d * ny
        val a = edgeIndex
        val b = (edgeIndex + 1) % 4
        val xs = floatArrayOf(xs0[0], xs0[1], xs0[2], xs0[3])
        val ys = floatArrayOf(ys0[0], ys0[1], ys0[2], ys0[3])
        xs[a] += dx; ys[a] += dy
        xs[b] += dx; ys[b] += dy
        if (!isQuadValid(xs, ys) || signedArea(xs, ys) * signedArea(xs0, ys0) <= 0f) {
            return EdgeTranslation(xs0.copyOf(), ys0.copyOf(), false, 0f, 0f)
        }
        return EdgeTranslation(xs, ys, true, dx, dy)
    }

    fun signedArea(xs: FloatArray, ys: FloatArray): Float {
        var sum = 0f
        for (i in 0..3) {
            val j = (i + 1) % 4
            sum += xs[i] * ys[j] - xs[j] * ys[i]
        }
        return 0.5f * sum
    }

    /** Unit normal of edge edgeIndex pointing away from the quad interior. */
    fun outwardUnitNormal(xs: FloatArray, ys: FloatArray, edgeIndex: Int): FloatArray {
        val a = edgeIndex
        val b = (edgeIndex + 1) % 4
        val c = (edgeIndex + 2) % 4
        val d = (edgeIndex + 3) % 4
        val ex = xs[b] - xs[a]
        val ey = ys[b] - ys[a]
        val len = hypot(ex, ey)
        if (len < 1e-6f) return floatArrayOf(0f, 0f)
        val n1x = -ey / len
        val n1y = ex / len
        val midABx = 0.5f * (xs[a] + xs[b])
        val midABy = 0.5f * (ys[a] + ys[b])
        val midCDx = 0.5f * (xs[c] + xs[d])
        val midCDy = 0.5f * (ys[c] + ys[d])
        val outx = midABx - midCDx
        val outy = midABy - midCDy
        return if (n1x * outx + n1y * outy >= 0f) floatArrayOf(n1x, n1y)
        else floatArrayOf(-n1x, -n1y)
    }

    fun isQuadValid(xs: FloatArray, ys: FloatArray): Boolean {
        for (i in 0..3) {
            val j = (i + 1) % 4
            val dx = xs[j] - xs[i]
            val dy = ys[j] - ys[i]
            if (dx * dx + dy * dy < 1f) return false
        }
        var sign = 0
        for (i in 0..3) {
            val j = (i + 1) % 4
            val k = (i + 2) % 4
            val v1x = xs[j] - xs[i]
            val v1y = ys[j] - ys[i]
            val v2x = xs[k] - xs[j]
            val v2y = ys[k] - ys[j]
            val cross = v1x * v2y - v1y * v2x
            val s = when { cross > 0f -> 1; cross < 0f -> -1; else -> 0 }
            if (s == 0) return false
            if (sign == 0) sign = s else if (sign != s) return false
        }
        return true
    }

    // ===== Snap-to-Right-Angle assist =====

    const val SNAP_ENTER_DEG = 1.5f
    const val SNAP_EXIT_DEG = 3.0f

    class Result(
        val x: Double,
        val y: Double,
        val prevEdgeSnapped: Boolean,
        val nextEdgeSnapped: Boolean
    )

    fun snapEvaluate(
        corners: List<Pair<Double, Double>>,
        movingIndex: Int,
        newX: Double,
        newY: Double,
        prevEdgeActive: Boolean,
        nextEdgeActive: Boolean
    ): Result {
        if (corners.size != 4) return Result(newX, newY, false, false)
        if (movingIndex < 0 || movingIndex >= 4) return Result(newX, newY, prevEdgeActive, nextEdgeActive)

        val prev = (movingIndex + 3) % 4
        val next = (movingIndex + 1) % 4
        val prevFixed = corners[prev]
        val nextFixed = corners[next]

        val prevDec = decide(prevFixed, newX, newY, prevEdgeActive)
        val nextDec = decide(nextFixed, newX, newY, nextEdgeActive)

        var cx = newX
        var cy = newY

        var prevActive = prevDec.active
        var nextActive = nextDec.active

        val prevHorizontal = isProjectionHorizontal(prevFixed, newX, newY)
        val nextHorizontal = isProjectionHorizontal(nextFixed, newX, newY)
        if (prevActive && nextActive && prevHorizontal == nextHorizontal) {
            val prevDelta = minAxisDeltaDeg(prevFixed, newX, newY)
            val nextDelta = minAxisDeltaDeg(nextFixed, newX, newY)
            if (prevDelta <= nextDelta) nextActive = false else prevActive = false
        }
        if (prevActive) {
            val p = projectOntoNearestAxis(prevFixed, cx, cy)
            cx = p[0]; cy = p[1]
        }
        if (nextActive) {
            val p = projectOntoNearestAxis(nextFixed, cx, cy)
            cx = p[0]; cy = p[1]
        }

        if (prevActive || nextActive) {
            val cand = corners.toMutableList()
            cand[movingIndex] = cx to cy
            if (!isSimpleConvexQuad(cand)) {
                return Result(newX, newY, false, false)
            }
        }

        return Result(cx, cy, prevActive, nextActive)
    }

    private class EdgeDecision(val active: Boolean, val deltaDeg: Double)

    private fun decide(fixed: Pair<Double, Double>, mx: Double, my: Double, wasActive: Boolean): EdgeDecision {
        val delta = minAxisDeltaDeg(fixed, mx, my)
        if (delta.isNaN()) return EdgeDecision(false, 90.0)
        val active = if (wasActive) delta <= SNAP_EXIT_DEG else delta <= SNAP_ENTER_DEG
        return EdgeDecision(active, delta)
    }

    fun minAxisDeltaDeg(fixed: Pair<Double, Double>, mx: Double, my: Double): Double {
        val dx = mx - fixed.first
        val dy = my - fixed.second
        if (dx == 0.0 && dy == 0.0) return Double.NaN
        val angHoriz = Math.toDegrees(Math.atan2(abs(dy), abs(dx)))
        return minOf(angHoriz, 90.0 - angHoriz)
    }

    fun projectOntoNearestAxis(fixed: Pair<Double, Double>, mx: Double, my: Double): DoubleArray {
        return if (isProjectionHorizontal(fixed, mx, my)) {
            doubleArrayOf(mx, fixed.second)
        } else {
            doubleArrayOf(fixed.first, my)
        }
    }

    fun isProjectionHorizontal(fixed: Pair<Double, Double>, mx: Double, my: Double): Boolean =
        abs(mx - fixed.first) >= abs(my - fixed.second)

    fun isSimpleConvexQuad(q: List<Pair<Double, Double>>): Boolean {
        if (q.size != 4) return false
        var sign = 0
        for (i in 0..3) {
            val a = q[i]
            val b = q[(i + 1) % 4]
            val c = q[(i + 2) % 4]
            val cross = (b.first - a.first) * (c.second - b.second) - (b.second - a.second) * (c.first - b.first)
            if (cross == 0.0) return false
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (sign != s) return false
        }
        return true
    }
}
