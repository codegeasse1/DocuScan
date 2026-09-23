package com.docuscan.app.ui

import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import com.docuscan.app.scan.BitmapUtil
import com.docuscan.app.scan.Cleanup
import com.docuscan.app.scan.CropAspectRatio
import com.docuscan.app.scan.CropGeometry
import com.docuscan.app.scan.WarpTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

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
    var rotBitmap by remember { mutableStateOf(bitmap) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val fit = remember(boxSize, rotBitmap) {
        if (boxSize.width == 0 || boxSize.height == 0) {
            Rect(0f, 0f, 1f, 1f)
        } else {
            val bw = rotBitmap.width.toFloat()
            val bh = rotBitmap.height.toFloat()
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
    // Explicit redraw token so corner placement is reflected immediately in the overlay.
    var cropRevision by remember { mutableIntStateOf(0) }
    var userInteracted by remember { mutableStateOf(false) }
    var lastDraggedCorner by remember { mutableIntStateOf(-1) }
    var edgeDrag by remember { mutableStateOf<EdgeDrag?>(null) }
    var aspectRatio by remember { mutableStateOf(CropAspectRatio.AUTO) }
    var customRatioText by remember { mutableStateOf("1.4142") }
    var customDialog by remember { mutableStateOf(false) }
    var detecting by remember { mutableStateOf(false) }
    var refiningCorner by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    val snapActive = remember { mutableStateListOf(false, false, false, false) }
    var snapHighlight by remember { mutableIntStateOf(-1) }
    val scope = rememberCoroutineScope()

    fun corner(i: Int): Offset {
        val f = fit
        return Offset(f.left + norm[i * 2] * f.width, f.top + norm[i * 2 + 1] * f.height)
    }

    fun setViewCorner(i: Int, x: Float, y: Float) {
        val f = fit
        norm[i * 2] = ((x - f.left) / f.width).coerceIn(-0.05f, 1.05f)
        norm[i * 2 + 1] = ((y - f.top) / f.height).coerceIn(-0.05f, 1.05f)
        cropRevision++
    }

    fun reset() {
        norm[0] = 0.02f; norm[1] = 0.02f
        norm[2] = 0.98f; norm[3] = 0.02f
        norm[4] = 0.98f; norm[5] = 0.98f
        norm[6] = 0.02f; norm[7] = 0.98f
    }

    /** Places an axis-aligned rect of the given view-space size, centered on (cx, cy), inside the image. */
    fun setRectAround(cx: Float, cy: Float, w: Float, h: Float) {
        val f = fit
        var left = cx - w / 2f
        var top = cy - h / 2f
        var right = left + w
        var bottom = top + h
        if (left < f.left) { right += f.left - left; left = f.left }
        if (right > f.right) { left -= right - f.right; right = f.right }
        if (top < f.top) { bottom += f.top - top; top = f.top }
        if (bottom > f.bottom) { top -= bottom - f.bottom; bottom = f.bottom }
        setViewCorner(0, left, top)
        setViewCorner(1, right, top)
        setViewCorner(2, right, bottom)
        setViewCorner(3, left, bottom)
    }

    /** Reshapes the crop box to the given short/long ratio, keeping it centered on the current box. */
    fun reshapeToRatio(shortOverLong: Double) {
        if (!(shortOverLong > 0.0) || shortOverLong > 1.0 || !shortOverLong.isFinite()) return
        val f = fit
        val xs = (0..3).map { corner(it).x }
        val ys = (0..3).map { corner(it).y }
        val cx = (xs.min() + xs.max()) / 2f
        val cy = (ys.min() + ys.max()) / 2f
        val bboxW = (xs.max() - xs.min()).coerceAtLeast(1f)
        val bboxH = (ys.max() - ys.min()).coerceAtLeast(1f)
        val landscape = bboxW >= bboxH
        val ratioWh = if (landscape) (1.0 / shortOverLong).toFloat() else shortOverLong.toFloat()
        var w = bboxW
        var h = w / ratioWh
        if (h > bboxH) { h = bboxH; w = h * ratioWh }
        if (w > f.width) { w = f.width; h = w / ratioWh }
        if (h > f.height) { h = f.height; w = h * ratioWh }
        if (w < 24f || h < 24f) return
        setRectAround(cx, cy, w, h)
    }

    suspend fun detectEdges(): Boolean {
        detecting = true
        val pts = withContext(Dispatchers.Default) {
            runCatching { Cleanup.detectCorners(rotBitmap) }.getOrNull()
        }
        detecting = false
        if (userInteracted) {
            return false
        }
        if (pts == null || pts.size != 4) {
            hint = "Couldn't detect page edges — drag the corners or pick a size."
            return false
        }
        // The user may have touched the image while OpenCV was finishing.
        // Re-check immediately before committing the detected quad.
        if (userInteracted) {
            return false
        }
        for (i in 0..3) {
            norm[i * 2] = (pts[i].x / rotBitmap.width).coerceIn(0f, 1f)
            norm[i * 2 + 1] = (pts[i].y / rotBitmap.height).coerceIn(0f, 1f)
        }
        cropRevision++
        hint = "Auto-detected the page edges — fine-tune if needed."
        return true
    }


    /**
     * Tap interaction:
     *  - near a corner: move that corner exactly to the finger
     *  - otherwise: move the nearest crop edge so the edge reaches the finger
     *
     * This is deliberately geometry based. A four-quadrant rule cannot express
     * "the closer border" and was the reason taps in the middle/side areas felt
     * like they did nothing or moved the wrong handle.
     */
    fun moveHandleToTap(pos: Offset) {
        val f = fit
        if (f.width <= 1f || f.height <= 1f) return

        val x = pos.x.coerceIn(f.left, f.right)
        val y = pos.y.coerceIn(f.top, f.bottom)
        val tap = Offset(x, y)

        val corners = Array(4) { corner(it) }
        var nearestCorner = -1
        var nearestCornerDistance = Float.POSITIVE_INFINITY
        for (i in 0..3) {
            val d = (corners[i] - tap).getDistance()
            if (d < nearestCornerDistance) {
                nearestCornerDistance = d
                nearestCorner = i
            }
        }

        // A generous touch zone makes a page corner easy to correct even when
        // auto-detection placed the crop handle somewhat away from it.
        val cornerZone = maxOf(72.dp.toPx(), minOf(f.width, f.height) * 0.14f)
        if (nearestCorner >= 0 &&
            (nearestCornerDistance <= cornerZone || lastDraggedCorner == nearestCorner)
        ) {
            setViewCorner(nearestCorner, x, y)
            lastDraggedCorner = nearestCorner
            hint = "Corner moved to the tap."
            return
        }

        var nearestEdge = -1
        var nearestEdgeDistance = Float.POSITIVE_INFINITY
        for (i in 0..3) {
            val j = (i + 1) % 4
            val projection = CropGeometry.projectOntoSegment(
                x, y,
                corners[i].x, corners[i].y,
                corners[j].x, corners[j].y
            )
            if (projection.perpDist < nearestEdgeDistance) {
                nearestEdgeDistance = projection.perpDist
                nearestEdge = i
            }
        }

        if (nearestEdge >= 0) {
            val xs = FloatArray(4) { corners[it].x }
            val ys = FloatArray(4) { corners[it].y }
            val a = nearestEdge
            val b = (nearestEdge + 1) % 4
            val nxNy = CropGeometry.outwardUnitNormal(xs, ys, nearestEdge)
            val m0x = (xs[a] + xs[b]) / 2f
            val m0y = (ys[a] + ys[b]) / 2f
            val moved = CropGeometry.applyEdgeTranslation(
                xs, ys, nearestEdge,
                m0x, m0y, nxNy[0], nxNy[1],
                x, y
            )
            if (moved.applied) {
                for (i in 0..3) setViewCorner(i, moved.xs[i], moved.ys[i])
                lastDraggedCorner = -1
                hint = "Nearest crop border moved to the tap."
                return
            }
        }

        // Degenerate/invalid geometry fallback: place the nearest corner.
        if (nearestCorner >= 0) {
            setViewCorner(nearestCorner, x, y)
            lastDraggedCorner = nearestCorner
            hint = "Corner moved to the tap."
        }
    }


    fun selectPreset(r: CropAspectRatio) {
        when (r) {
            CropAspectRatio.CUSTOM -> { customDialog = true; return }
            CropAspectRatio.AUTO -> {
                userInteracted = false
                aspectRatio = CropAspectRatio.AUTO
                scope.launch { detectEdges() }
            }
            CropAspectRatio.ORIGINAL -> {
                userInteracted = true
                aspectRatio = CropAspectRatio.ORIGINAL
                reset()
                cropRevision++
                hint = null
            }
            else -> {
                userInteracted = true
                aspectRatio = r
                r.shortOverLong()?.let { reshapeToRatio(it) }
                hint = null
            }
        }
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
                ((p.x - f.left) / f.width) * rotBitmap.width,
                ((p.y - f.top) / f.height) * rotBitmap.height
            )
        }
        // MakeACopy warp target-size semantics: Auto = Zhang & He projective estimate,
        // Original = pixel-distance heuristic, fixed ratios = explicit short/long.
        val mode: WarpTarget.WarpMode
        val ratio: Double?
        when (aspectRatio) {
            CropAspectRatio.AUTO -> {
                mode = WarpTarget.WarpMode.AUTO_PROJECTIVE
                ratio = null
            }
            CropAspectRatio.ORIGINAL -> {
                mode = WarpTarget.WarpMode.LEGACY_HEURISTIC
                ratio = null
            }
            CropAspectRatio.CUSTOM -> {
                mode = WarpTarget.WarpMode.FIXED_RATIO
                ratio = customRatioText.replace(',', '.').toFloatOrNull()?.toDouble()
            }
            else -> {
                mode = WarpTarget.WarpMode.FIXED_RATIO
                ratio = aspectRatio.shortOverLong()
            }
        }
        val (outW, outH) = WarpTarget.compute(q.toTypedArray(), mode, ratio, rotBitmap.width, rotBitmap.height)
        onApply(BitmapUtil.perspectiveWarp(rotBitmap, q, outW, outH))
    }

    fun rotateLeft() {
        userInteracted = false
        rotBitmap = BitmapUtil.rotate90(rotBitmap)
        reset()
        cropRevision++
    }

    fun rotateRight() {
        userInteracted = false
        rotBitmap = BitmapUtil.rotate90(BitmapUtil.rotate90(BitmapUtil.rotate90(rotBitmap)))
        reset()
        cropRevision++
    }

    // When the crop screen opens (and after each rotate) try to place the box on the page edges.
    LaunchedEffect(rotBitmap) {
        detectEdges()
    }

    val accent = MaterialTheme.colorScheme.primary
    val accentInt = accent.toArgbCompat()

    Column(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ===== Image area: the crop canvas only - no controls overlap it =====
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .onSizeChanged { boxSize = it }
        ) {
            Canvas(
                Modifier
                    .fillMaxSize()
                    // Tap and drag are intentionally separate gesture detectors.
                    // Compose's drag detector cancels tap once touch-slop is crossed, so a
                    // tap cannot accidentally be treated as a drag and vice versa.
                    .pointerInput(boxSize, rotBitmap) {
                        detectTapGestures { pos ->
                            userInteracted = true
                            moveHandleToTap(pos)
                            for (i in 0..3) snapActive[i] = false
                            snapHighlight = -1
                        }
                    }
                    .pointerInput(boxSize, rotBitmap) {
                        detectDragGestures(
                            onDragStart = { start ->
                                userInteracted = true

                                val cornerHitRadius = 56.dp.toPx()
                                var selectedCorner = -1
                                var bestCornerDistance = Float.POSITIVE_INFINITY
                                for (i in 0..3) {
                                    val d = (corner(i) - start).getDistance()
                                    if (d <= cornerHitRadius && d < bestCornerDistance) {
                                        bestCornerDistance = d
                                        selectedCorner = i
                                    }
                                }

                                if (selectedCorner >= 0) {
                                    dragCorner = selectedCorner
                                    lastDraggedCorner = selectedCorner
                                    edgeDrag = null
                                    return@detectDragGestures
                                }

                                val xs = FloatArray(4) { corner(it).x }
                                val ys = FloatArray(4) { corner(it).y }
                                val edge = CropGeometry.findEdgeHit(xs, ys, start.x, start.y)
                                if (edge >= 0) {
                                    val b = (edge + 1) % 4
                                    val n = CropGeometry.outwardUnitNormal(xs, ys, edge)
                                    edgeDrag = EdgeDrag(
                                        edge,
                                        xs,
                                        ys,
                                        (xs[edge] + xs[b]) / 2f,
                                        (ys[edge] + ys[b]) / 2f,
                                        n[0],
                                        n[1]
                                    )
                                } else {
                                    dragCorner = -1
                                    edgeDrag = null
                                }
                            },
                            onDrag = { change, _ ->
                                change.consume()

                                val selectedCorner = dragCorner
                                if (selectedCorner >= 0) {
                                    val i = selectedCorner
                                    val newX = change.position.x.coerceIn(fit.left, fit.right)
                                    val newY = change.position.y.coerceIn(fit.top, fit.bottom)
                                    val corners = (0..3).map {
                                        corner(it).x.toDouble() to corner(it).y.toDouble()
                                    }
                                    val res = CropGeometry.snapEvaluate(
                                        corners,
                                        i,
                                        newX.toDouble(),
                                        newY.toDouble(),
                                        snapActive[(i + 3) % 4],
                                        snapActive[i]
                                    )
                                    setViewCorner(i, res.x.toFloat(), res.y.toFloat())
                                    snapActive[(i + 3) % 4] = res.prevEdgeSnapped
                                    snapActive[i] = res.nextEdgeSnapped
                                    snapHighlight = when {
                                        res.prevEdgeSnapped -> (i + 3) % 4
                                        res.nextEdgeSnapped -> i
                                        else -> -1
                                    }
                                    return@detectDragGestures
                                }

                                val ed = edgeDrag
                                if (ed != null) {
                                    val res = CropGeometry.applyEdgeTranslation(
                                        ed.xs0, ed.ys0, ed.edgeIndex,
                                        ed.m0x, ed.m0y, ed.nx, ed.ny,
                                        change.position.x, change.position.y
                                    )
                                    if (res.applied) {
                                        for (i in 0..3) {
                                            setViewCorner(i, res.xs[i], res.ys[i])
                                        }
                                    }
                                }
                            },
                            onDragEnd = {
                                dragCorner = -1
                                edgeDrag = null
                            },
                            onDragCancel = {
                                dragCorner = -1
                                edgeDrag = null
                            }
                        )
                    }            ) {
                // Read the revision in the draw scope so every tap/drag state mutation
                // forces the visible overlay to redraw immediately.
                val currentCropRevision = cropRevision
                if (currentCropRevision < 0) return@Canvas
                val f = fit
                val rect = RectF(f.left, f.top, f.right, f.bottom)
                val canvas = drawContext.canvas.nativeCanvas

                canvas.drawBitmap(rotBitmap, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))

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

            if (detecting || refiningCorner) {
                Surface(
                    modifier = Modifier.align(Alignment.Center),
                    shape = RoundedCornerShape(16.dp),
                    color = Color.Black.copy(alpha = 0.6f),
                    contentColor = Color.White
                ) {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text(if (detecting) "Detecting edges…" else "Refining corner…", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        // ===== Control panel (below the image, transparent glass) =====
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color.White.copy(alpha = 0.07f),
            contentColor = Color.White,
            shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
        ) {
            Column(
                Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    hint ?: "Drag corners or edges · edges snap to 90°",
                    color = Color.White.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Surface(
                        onClick = { rotateLeft() },
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color = Color.White.copy(alpha = 0.12f),
                        contentColor = Color.White
                    ) {
                        Text(
                            "⟲",
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text("Rotate", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.78f))
                    Surface(
                        onClick = { rotateRight() },
                        shape = androidx.compose.foundation.shape.CircleShape,
                        color = Color.White.copy(alpha = 0.12f),
                        contentColor = Color.White
                    ) {
                        Text(
                            "⟳",
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                LazyRow(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(CropAspectRatio.entries) { r ->
                        FilterChip(
                            selected = aspectRatio == r,
                            onClick = { selectPreset(r) },
                            label = { Text(r.label, color = Color.White) }
                        )
                    }
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onCancel) { Text("Cancel", color = Color.White) }
                    TextButton(onClick = { reset(); hint = null }) { Text("Reset", color = Color.White) }
                    TextButton(onClick = { applyCrop() }) {
                        Text("Crop", color = Color.White, fontWeight = FontWeight.Bold)
                    }
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
                        value = customRatioText,
                        onValueChange = { customRatioText = it.filter { c -> c.isDigit() || c == '.' || c == ',' } },
                        singleLine = true,
                        label = { Text("Ratio") }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val ratio = customRatioText.replace(',', '.').toFloatOrNull()?.toDouble()
                    if (ratio != null && ratio > 0.0) {
                        customDialog = false
                        aspectRatio = CropAspectRatio.CUSTOM
                        reshapeToRatio(if (ratio > 1.0) 1.0 / ratio else ratio)
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
