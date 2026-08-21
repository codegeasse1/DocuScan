package com.docuscan.app.ui

import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.docuscan.app.scan.AutoCrop
import com.docuscan.app.scan.BitmapUtil
import com.docuscan.app.scan.CropAspectRatio
import com.docuscan.app.scan.CropGeometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot

/** Frozen state captured at edge-drag touch-down (parallel translation). */
private class EdgeDrag(
    val edgeIndex: Int,
    val xs0: FloatArray,
    val ys0: FloatArray,
    val m0x: Float,
    val m0y: Float,
    val nx: Float,
    val ny: Float
)

@Composable
fun CropOverlay(bitmap: Bitmap, onApply: (Bitmap) -> Unit, onCancel: () -> Unit) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val scope = rememberCoroutineScope()
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
    var edgeDrag by remember { mutableStateOf<EdgeDrag?>(null) }
    var userDragged by remember { mutableStateOf(false) }
    var detecting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var aspectRatio by remember { mutableStateOf(CropAspectRatio.AUTO) }
    var customRatioText by remember { mutableStateOf("1.4142") }
    var customDialog by remember { mutableStateOf(false) }
    val snapActive = remember { mutableStateListOf(false, false, false, false) }
    var snapHighlight by remember { mutableIntStateOf(-1) }

    fun corner(i: Int): Offset {
        val f = fit
        return Offset(f.left + norm[i * 2] * f.width, f.top + norm[i * 2 + 1] * f.height)
    }

    fun setViewCorner(i: Int, x: Float, y: Float) {
        val f = fit
        norm[i * 2] = ((x - f.left) / f.width).coerceIn(-0.05f, 1.05f)
        norm[i * 2 + 1] = ((y - f.top) / f.height).coerceIn(-0.05f, 1.05f)
    }

    fun applyCrop() {
        var a = 0f
        for (i in 0..3) {
            val j = (i + 1) % 4
            a += norm[i * 2] * norm[j * 2 + 1] - norm[j * 2] * norm[i * 2 + 1]
        }
        if (abs(a) / 2f < 0.02f) return
        val f = fit
        val q = (0..3).map { idx ->
            val p = corner(idx)
            PointF(
                ((p.x - f.left) / f.width) * bitmap.width,
                ((p.y - f.top) / f.height) * bitmap.height
            )
        }
        // Output size: pixel-distance estimate, optionally constrained to an aspect ratio.
        fun seg(a: PointF, b: PointF) = hypot(b.x - a.x, b.y - a.y)
        var w = (seg(q[0], q[1]) + seg(q[3], q[2])) / 2f
        var h = (seg(q[0], q[3]) + seg(q[1], q[2])) / 2f
        val ratio: Double? = if (aspectRatio == CropAspectRatio.CUSTOM) {
            customRatioText.replace(',', '.').toFloatOrNull()?.toDouble()
        } else {
            aspectRatio.shortOverLong()
        }
        if (ratio != null) {
            if (w >= h) h = (w * ratio).toFloat() else w = (h * ratio).toFloat()
        }
        onApply(BitmapUtil.perspectiveWarp(bitmap, q, w.toInt().coerceAtLeast(2), h.toInt().coerceAtLeast(2)))
    }

    fun autoDetect() {
        scope.launch {
            detecting = true
            status = "Detecting page corners…"
            val pts = withContext(Dispatchers.IO) { AutoCrop.detectCorners(bitmap) }
            detecting = false
            when {
                pts != null && !userDragged -> {
                    for (i in 0..3) {
                        norm[i * 2] = (pts[i].x / bitmap.width).coerceIn(0.005f, 0.995f)
                        norm[i * 2 + 1] = (pts[i].y / bitmap.height).coerceIn(0.005f, 0.995f)
                    }
                    status = "Corners detected — drag them or tap Crop"
                }
                pts != null -> status = "Corners detected — drag them or tap Crop"
                else -> status = "No page found — drag the corners to crop"
            }
        }
    }

    fun reset() {
        norm[0] = 0.02f; norm[1] = 0.02f
        norm[2] = 0.98f; norm[3] = 0.02f
        norm[4] = 0.98f; norm[5] = 0.98f
        norm[6] = 0.02f; norm[7] = 0.98f
        status = "Reset — drag the corners to crop"
    }

    LaunchedEffect(Unit) { autoDetect() }

    val accent = MaterialTheme.colorScheme.primary
    val accentInt = accent.toArgbCompat()

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onSizeChanged { boxSize = it }
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(boxSize) {
                    detectDragGestures(
                        onDragStart = { pos ->
                            userDragged = true
                            // Corner hit first (larger radius), then edge hit.
                            var best = -1
                            var bestD = 70f
                            for (i in 0..3) {
                                val d = (corner(i) - pos).getDistance()
                                if (d < bestD) {
                                    bestD = d
                                    best = i
                                }
                            }
                            if (best >= 0) {
                                dragCorner = best
                                edgeDrag = null
                                return@detectDragGestures
                            }
                            val xs = FloatArray(4) { corner(it).x }
                            val ys = FloatArray(4) { corner(it).y }
                            val e = CropGeometry.findEdgeHit(xs, ys, pos.x, pos.y)
                            if (e >= 0) {
                                val a = e
                                val b = (e + 1) % 4
                                val m0x = (xs[a] + xs[b]) / 2f
                                val m0y = (ys[a] + ys[b]) / 2f
                                val n = CropGeometry.outwardUnitNormal(xs, ys, e)
                                edgeDrag = EdgeDrag(e, xs, ys, m0x, m0y, n[0], n[1])
                                dragCorner = -1
                            }
                        },
                        onDrag = { change, _ ->
                            val ed = edgeDrag
                            if (ed != null) {
                                val res = CropGeometry.applyEdgeTranslation(
                                    ed.xs0, ed.ys0, ed.edgeIndex,
                                    ed.m0x, ed.m0y, ed.nx, ed.ny,
                                    change.position.x, change.position.y
                                )
                                if (res.applied) {
                                    for (i in 0..3) setViewCorner(i, res.xs[i], res.ys[i])
                                }
                            } else if (dragCorner >= 0) {
                                val i = dragCorner
                                val newX = change.position.x.coerceIn(fit.left, fit.right)
                                val newY = change.position.y.coerceIn(fit.top, fit.bottom)
                                val corners = (0..3).map { corner(it).x.toDouble() to corner(it).y.toDouble() }
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
                            edgeDrag = null
                            for (i in 0..3) snapActive[i] = false
                            snapHighlight = -1
                        },
                        onDragCancel = {
                            dragCorner = -1
                            edgeDrag = null
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

            val c0 = corner(0)
            val c1 = corner(1)
            val c2 = corner(2)
            val c3 = corner(3)

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

            val corners = arrayOf(c0, c1, c2, c3)

            // Edge midpoint handles (parallel edge dragging)
            val midPaint = Paint().apply {
                color = android.graphics.Color.WHITE
                style = Paint.Style.STROKE
                strokeWidth = 3.dp.toPx()
            }
            for (i in 0..3) {
                val a = corners[i]
                val b = corners[(i + 1) % 4]
                val mx = (a.x + b.x) / 2f
                val my = (a.y + b.y) / 2f
                canvas.drawLine(mx - 6.dp.toPx(), my, mx + 6.dp.toPx(), my, midPaint)
                canvas.drawLine(mx, my - 6.dp.toPx(), mx, my + 6.dp.toPx(), midPaint)
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
                val c = corner(i)
                canvas.drawCircle(c.x, c.y, 14.dp.toPx(), Paint().apply { color = android.graphics.Color.WHITE })
                canvas.drawCircle(c.x, c.y, 9.dp.toPx(), Paint().apply { color = accentInt })
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 8.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onCancel) { Text("Cancel", color = Color.White) }
                Text(
                    if (detecting) "Detecting…" else status,
                    modifier = Modifier.weight(1f),
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center
                )
                TextButton(onClick = { autoDetect() }) {
                    Text("Auto", color = Color.White, fontWeight = FontWeight.SemiBold)
                }
                TextButton(onClick = { reset() }) {
                    Text("Reset", color = Color.White)
                }
                TextButton(onClick = { applyCrop() }) {
                    Text("Crop", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

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
            Text(
                "Drag corners or edges · edges snap to 90°",
                modifier = Modifier.fillMaxWidth(),
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center
            )
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
                        value = customRatioText,
                        onValueChange = { customRatioText = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                        singleLine = true,
                        label = { Text("Ratio") }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (customRatioText.toFloatOrNull() != null) {
                        customDialog = false
                        aspectRatio = CropAspectRatio.CUSTOM
                    }
                }) { Text("Use") }
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
