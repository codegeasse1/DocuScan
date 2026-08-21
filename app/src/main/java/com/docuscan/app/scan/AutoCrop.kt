package com.docuscan.app.scan

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.max

/**
 * Detect the document region in an image.
 *
 * Approach: downscale the image, compute a luminance gradient (edge strength),
 * threshold it, then find the largest 8-connected edge component via
 * union-find. The bounding box of that component (with a small pad) is the
 * document region. Returns null when no convincing document is found.
 */
object AutoCrop {

    fun findBounds(src: Bitmap): Rect? {
        if (src.width < 24 || src.height < 24) return null
        val maxDim = 480
        val scale = max(0.1f, Math.min(1f, maxDim.toFloat() / max(src.width, src.height)))
        val w = max(16, (src.width * scale).toInt())
        val h = max(16, (src.height * scale).toInt())

        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        small.recycle()

        val lum = FloatArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            lum[i] = 0.299f * ((c shr 16) and 255) + 0.587f * ((c shr 8) and 255) + 0.114f * (c and 255)
        }

        val mag = FloatArray(w * h)
        var sum = 0.0
        for (y in 1 until h - 1) {
            var i = y * w + 1
            for (x in 1 until w - 1) {
                val gx = lum[i + 1] - lum[i - 1]
                val gy = lum[i + w] - lum[i - w]
                val m = gx * gx + gy * gy
                mag[i] = m
                sum += m
                i++
            }
        }
        val mean = sum / ((w - 2) * (h - 2))
        val thr = max(mean * 2.0 + 900.0, 1400.0).toFloat()

        val active = BooleanArray(w * h)
        for (i in px.indices) active[i] = mag[i] > thr

        val parent = IntArray(w * h) { -1 }

        fun find(a: Int): Int {
            var r = a
            while (parent[r] >= 0) r = parent[r]
            var cur = a
            while (cur != r) {
                val nxt = parent[cur]
                parent[cur] = r
                cur = nxt
            }
            return r
        }

        fun union(a: Int, b: Int) {
            var ra = find(a)
            var rb = find(b)
            if (ra == rb) return
            if (parent[ra] > parent[rb]) {
                val t = ra
                ra = rb
                rb = t
            }
            parent[ra] += parent[rb]
            parent[rb] = ra
        }

        for (y in 0 until h) {
            var i = y * w
            for (x in 0 until w) {
                if (active[i]) {
                    if (x < w - 1 && active[i + 1]) union(i, i + 1)
                    if (y < h - 1 && active[i + w]) union(i, i + w)
                    if (x < w - 1 && y < h - 1 && active[i + w + 1]) union(i, i + w + 1)
                    if (x > 0 && y < h - 1 && active[i + w - 1]) union(i, i + w - 1)
                }
                i++
            }
        }

        val count = IntArray(w * h)
        val minX = IntArray(w * h) { Int.MAX_VALUE }
        val maxX = IntArray(w * h)
        val minY = IntArray(w * h) { Int.MAX_VALUE }
        val maxY = IntArray(w * h)
        for (y in 0 until h) {
            var i = y * w
            for (x in 0 until w) {
                if (active[i]) {
                    val r = find(i)
                    count[r]++
                    if (x < minX[r]) minX[r] = x
                    if (x > maxX[r]) maxX[r] = x
                    if (y < minY[r]) minY[r] = y
                    if (y > maxY[r]) maxY[r] = y
                }
                i++
            }
        }

        var best = -1
        var bestN = 0
        for (i in active.indices) {
            if (count[i] > bestN) {
                bestN = count[i]
                best = i
            }
        }
        if (best < 0) return null

        val bw = maxX[best] - minX[best] + 1
        val bh = maxY[best] - minY[best] + 1
        if (bw * bh < (w * h) * 0.03) return null
        if (bw >= w - 2 && bh >= h - 2) return null

        val padX = (bw * 0.02f).toInt() + 1
        val padY = (bh * 0.02f).toInt() + 1
        val l = (minX[best] - padX).coerceAtLeast(0)
        val t = (minY[best] - padY).coerceAtLeast(0)
        val r = (maxX[best] + padX).coerceAtMost(w - 1)
        val b = (maxY[best] + padY).coerceAtMost(h - 1)

        val sL = l.toLong() * src.width / w
        val sT = t.toLong() * src.height / h
        val sR = (r + 1).toLong() * src.width / w
        val sB = (b + 1).toLong() * src.height / h
        return Rect(
            sL.toInt().coerceIn(0, src.width),
            sT.toInt().coerceIn(0, src.height),
            sR.toInt().coerceIn(0, src.width),
            sB.toInt().coerceIn(0, src.height)
        )
    }
}
