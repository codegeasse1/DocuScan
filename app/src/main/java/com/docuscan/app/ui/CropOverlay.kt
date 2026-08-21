package com.docuscan.app.ui

import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.docuscan.app.scan.BitmapUtil
import com.docuscan.app.scan.CropAspectRatio
import com.docuscan.app.scan.CropGeometry
import kotlin.math.abs
import kotlin.math.hypot

@Composable
fun CropOverlay(bitmap: Bitmap, onApply: (Bitmap) -> Unit, onCancel: () -> Unit) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val fit = remember(boxSize) {
        if (boxSize.width == 0 || boxSize.height == 0) {
            Rect(0f, 0f, 1f, 1f)
        } else {
            val bw = bitmap.width.toFloat()
            val bh = bitmap.height.toFloat()
            val s = minOf(boxSize.width / bw, boxSize.height / bh)
            val w = bw * s
            val h = bh * s
            Rect(
                (boxSize.width - w) / 2f,
                (boxSize.height - h) / 2f,
                (boxSize.width + w) / 2f,
                (boxSize.height + h) / 2f
            )
        }
    }

    // Normalized corners (0..1) inside the fit rect: TL, TR, BR, BL
    val norm = remember { mutableStateListOf(0.02f, 0.02f, 0.98f, 0.02f, 0.98f, 0.98f, 0.02f, 0.98f) }
    var dragCorner by remember { mutableIntStateOf(-1) }
    var dragEdge by remember { mutableIntStateOf(-1) }
    var customRatio by remember { mutableStateOf(1.4142f) }
    var customDialog by remember { mutableStateOf(false) }
    var aspectRatio by remember { mutableStateOf(CropAspectRatio.AUTO) }
    var snapActive by remember { mutableStateListOf(false, false, false, false) }
    var snapHighlight by remember { mutableIntStateOf(-1) }

    fun viewCorner(i: Int): Offset {
        val f = fit
        return Offset(f.left + norm[i * 2] * f.width, f.top + norm[i * 2 + 1] * f.height)
    }

    fun viewCornerPairs(): List<Pair<Double, Double>> =
        (0..3).map { viewCorner(it).x.toDouble() to viewCorner(it).y.toDouble() }

    fun setViewCorner(i: Int, x: Float, y: Float) {
        val f = fit
        norm[i * 2] = ((x - f.left) / f.width).coerceIn(0.001f, 0.999f)
        norm[i * 2 + 1] = ((y - f.top) / f.height).coerceIn(0.001f, 0.999f)
    }

    // Frozen edge-drag state (captured at touch-down)
    val edgeXs0 = remember { FloatArray(4) }
    val edgeYs0 = remember { FloatArray(4) }
    var edgeIdx by remember { mutableIntStateOf(-1) }
    var edgeM0x by remember { mutableStateOf(0f) }
    var edgeM0y by remember { mutableStateOf(0f) }
    var edgeNx by remember { mutableStateOf(0f) }
    var edgeNy by remember { mutableStateOf(0f) }

    val accent = MaterialTheme.colorScheme.primary
    val accentInt = accent.toArgbCompat()

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { size = it }
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(boxSize) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            // Corner hit first (larger radius), then edge hit.
                            var best = -1
                            var bestD = 70f
                            for (i in 0..3) {
                                val d = (viewCorner(i) - pos).getDistance()
                                if (d < bestD) {
                                    bestD = d
                                    best = i
                                }
                            }
                            if (best >= 0) {
                                dragCorner = best
                                dragEdge = -1
                                return@detectDragGestures
                            }
                            val xs = FloatArray(4) { viewCorner(it).x }
                            val ys = FloatArray(4) { viewCorner(it).y }
                            val e = CropGeometry.findEdgeHit(xs, ys, pos.x, pos.y)
                            if (e >= 0) {
                                dragEdge = e
                                dragCorner = -1
                                edgeIdx = e
                                val a = e
                                val b = (e + 1) % 4
                                for (i in 0..3) {
                                    val c = viewCorner(i)
                                    edgeXs0[i] = c.x
                                    edgeYs0[i] = c.y
                                }
                                edgeM0x = (edgeXs0[a] + edgeXs0[b]) / 2f
                                edgeM0y = (edgeYs0[a] + edgeYs0[b]) / 2f
                                val n = CropGeometry.outwardUnitNormal(edgeXs0, edgeYs0, e)
                                edgeNx = n[0]
                                edgeNy = n[1]
                            }
                        },
                        onDrag = { change, _ ->
                            if (dragEdge >= 0) {
                                val res = CropGeometry.applyEdgeTranslation(
                                    edgeXs0, edgeYs0, edgeIdx,
                                    edgeM0x, edgeM0y, edgeNx, edgeNy,
                                    change.position.x, change.position.y
                                )
                                if (res.applied) {
                                    for (i in 0..3) setViewCorner(i, res.xs[i], res.ys[i])
                                }
                            } else if (dragCorner >= 0) {
                                val i = dragCorner
                                val newX = change.position.x.coerceIn(fit.left, fit.right)
                                val newY = change.position.y.coerceIn(fit.top, fit.bottom)
                                val corners = viewCornerPairs()
                                val res = CropGeometry.snapEvaluate(
                                    corners, i,
                                    newX.toDouble(), newY.toDouble(),
                                    snapActive[(i + 3) % 4], snapActive[i]
                                )
                                setViewCorner(i, res.x.toFloat(), res.y.toFloat())
                                snapActive[(i + 3) % 4] = res.prevEdgeSnapped
                                snapActive[i] = res.nextEdgeSnapped
                                snapHighlight = when {
                                    res.prevEdgeSnapped -> (i + 3) % 4
                                    res.nextEdgeSnapped -> i
                                    else -> -1
                                }
                            }
                            change.consume()
                        },
                        onDragEnd = {
                            dragCorner = -1
                            dragEdge = -1
                            edgeIdx = -1
                            for (i in 0..3) snapActive[i] = false
                            snapHighlight = -1
                        },
                        onDragCancel = {
                            dragCorner = -1
                            dragEdge = -1
                            edgeIdx = -1
                            for (i in 0..3) snapActive[i] = false
                            snapHighlight = -1
                        }
                    )
                }
        ) {
            val f = fit
            val rect = RectF(f.left, f.top, f.right, f.bottom)
            val canvas = drawContext.canvas.nativeCanvas

            canvas.drawBitmap(bitmap, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))

            val c0 = viewCorner(0)
            val c1 = viewCorner(1)
            val c2 = viewCorner(2)
            val c3 = viewCorner(3)

            val quad = Path().apply {
                moveTo(c0.x, c0.y)
                lineTo(c1.x, c1.y)
                lineTo(c2.x, c2.y)
                lineTo(c3.x, c3.y)
                close()
            }

            val mask = Path().apply {
                addRect(RectF(0f, 0f, size.width, size.height), Path.Direction.CW)
                addPath(quad, 0f, 0f)
                setFillType(Path.FillType.EVEN_ODD)
            }
            canvas.drawPath(mask, Paint().apply { color = android.graphics.Color.argb(150, 0, 0, 0) })

            // Rule-of-thirds grid
            val gridPaint = Paint().apply {
                color = android.graphics.Color.argb(90, 255, 255, 255)
                strokeWidth = 1.dp.toPx()
            }
            for (i in 1..2) {
                val x = f.left + f.width * i / 3f
                canvas.drawLine(x, f.top, x, f.bottom, gridPaint)
                val y = f.top + f.height * i / 3f
                canvas.drawLine(f.left, y, f.right, y, gridPaint)
            }

            canvas.drawPath(quad, Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = 2.dp.toPx()
                color = accentInt
            })

            // Edge midpoint handles (parallel edge dragging)
            val midPaint = Paint().apply {
                color = android.graphics.Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 3.dp.toPx()
            }
            val corners = arrayOf(c0, c1, c2, c3)
            for (i in 0..3) {
                val a = corners[i]
                val b = corners[(i + 1) % 4]
                canvas.drawLine(
                    (a.x + b.x) / 2f - 6.dp.toPx(), (a.y + b.y) / 2f,
                    (a.x + b.x) / 2f + 6.dp.toPx(), (a.y + b.y) / 2f,
                    midPaint
                )
                canvas.drawLine(
                    (a.x + b.x) / 2f, (a.y + b.y) / 2f - 6.dp.toPx(),
                    (a.x + b.x) / 2f, (a.y + b.y) / 2f + 6.dp.toPx(),
                    midPaint
                )
            }

            // Snap-to-right-angle highlight: brighter, thicker edge
            if (snapHighlight in 0..3) {
                val a = corners[snapHighlight]
                val b = corners[(snapHighlight + 1) % 4]
                canvas.drawLine(a.x, a.y, b.x, b.y, Paint().apply {
                    color = Color.White.toArgbCompat()
                    style = Paint.Style.STROKE
                    strokeWidth = 5.dp.toPx()
                })
            }

            for (i in 0..3) {
                val c = viewCorner(i)
                canvas.drawCircle(c.x, c.y, 14.dp.toPx(), Paint().apply { color = android.graphics.Color.WHITE })
                canvas.drawCircle(c.x, c.y, 9.dp.toPx(), Paint().apply { color = accentInt })
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onCancel) { Text("Cancel", color = Color.White) }
                Text("Adjust corners", color = Color.White.copy(alpha = 0.8f))
                TextButton(onClick = {
                    // Validate quad area (shoelace, normalized coords)
                    var a = 0f
                    for (i in 0..3) {
                        val j = (i + 1) % 4
                        a += norm[i * 2] * norm[j * 2 + 1] - norm[j * 2] * norm[i * 2 + 1]
                    }
                    if (abs(a) / 2f < 0.02f) return@TextButton
                    val f = fit
                    val q = (0..3).map { idx ->
                        val p = viewCorner(idx)
                        PointF(
                            ((p.x - f.left) / f.width) * bitmap.width,
                            ((p.y - f.top) / f.height) * bitmap.height
                        )
                    }
                    // Output size: pixel-distance estimate, optionally constrained to a ratio
                    fun seg(a: PointF, b: PointF) = hypot(b.x - a.x, b.y - a.y)
                    var w = (seg(q[0], q[1]) + seg(q[3], q[2])) / 2f
                    var h = (seg(q[0], q[3]) + seg(q[1], q[2])) / 2f
                    val ratio = if (aspectRatio == CropAspectRatio.CUSTOM) customRatio.toDouble()
                    else aspectRatio.shortOverLong()
                    if (ratio != null) {
                        if (w >= h) h = (w * ratio).toFloat()
                        else w = (h * ratio).toFloat()
                    }
                    val outW = w.toInt().coerceAtLeast(2)
                    val outH = h.toInt().coerceAtLeast(2)
                    onApply(BitmapUtil.perspectiveWarp(bitmap, q, outW, outH))
                }) {
                    Text("Apply", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            Text(
                "Drag corners or edges · edges snap to 90°",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.6f)
            )

            LazyRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(CropAspectRatio.entries) { r ->
                    FilterChip(
                        selected = aspectRatio == r,
                        onClick = {
                            if (r == CropAspectRatio.CUSTOM) customDialog = true else aspectRatio = r
                        },
                        label = { Text(r.label, color = Color.White) }
                    )
                }
            }
        }
    }

    if (customDialog) {
        AlertDialog(
            onDismissRequest = { customDialog = false },
            title = { Text("Custom aspect ratio") },
            text = {
                Column {
                    Text("Enter the short : long ratio (e.g. 0.71 for A4, 1.0 for square).")
                    OutlinedTextField(
                        value = customRatio.toString(),
                        onValueChange = { v ->
                            val p = v.replace(',', '.').toFloatOrNull()
                            if (p != null && p in 0.1f..10f) customRatio = p
                        },
                        singleLine = true,
                        label = { Text("Ratio") }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { customDialog = false; aspectRatio = CropAspectRatio.CUSTOM }) {
                    Text("Use")
                }
            },
            dismissButton = {
                TextButton(onClick = { customDialog = false }) { Text("Cancel") }
            }
        )
    }
}

private fun Color.toArgbCompat(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(),
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt()
)
