package com.docuscan.app.ui

import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitDragOrCancellation
import androidx.compose.foundation.gestures.drag
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
import androidx.compose.ui.input.pointer.PointerEventPass
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
        if (pts == null || pts.size != 4) {
            hint = "Couldn't detect page edges — drag the corners or pick a size."
            return false
        }
        for (i in 0..3) {
            norm[i * 2] = (pts[i].x / rotBitmap.width).coerceIn(0f, 1f)
            norm[i * 2 + 1] = (pts[i].y / rotBitmap.height).coerceIn(0f, 1f)
        }
        hint = "Auto-detected the page edges — fine-tune if needed."
        return true
    }


    suspend fun refineCornerAtViewPoint(viewX: Float, viewY: Float): Boolean {
        val f = fit
        if (f.width <= 1f || f.height <= 1f) return false

        // Convert the tap from the displayed image into original bitmap pixels.
        val imageX = ((viewX - f.left) / f.width * rotBitmap.width)
            .coerceIn(0f, rotBitmap.width.toFloat())
        val imageY = ((viewY - f.top) / f.height * rotBitmap.height)
            .coerceIn(0f, rotBitmap.height.toFloat())

        refiningCorner = true
        val refined = withContext(Dispatchers.Default) {
            runCatching { Cleanup.refineCornerNear(rotBitmap, imageX, imageY) }.getOrNull()
        }
        refiningCorner = false

        if (refined == null) return false

        // If the auto box is badly displaced, nearest-corner is unreliable.
        // Use the tap's position relative to the current crop center to choose
        // the intended TL/TR/BR/BL corner.
        val imageCenterX = (f.left + f.right) * 0.5f
        val imageCenterY = (f.top + f.bottom) * 0.5f
        val target = when {
            viewX < imageCenterX && viewY < imageCenterY -> 0
            viewX >= imageCenterX && viewY < imageCenterY -> 1
            viewX >= imageCenterX && viewY >= imageCenterY -> 2
            else -> 3
        }

        norm[target * 2] = (refined.x / rotBitmap.width).coerceIn(0f, 1f)
        norm[target * 2 + 1] = (refined.y / rotBitmap.height).coerceIn(0f, 1f)
        hint = "Corner refined — tap another corner if needed, or drag to fine-tune."
        return true
    }

    fun selectPreset(r: CropAspectRatio) {
        when (r) {
            CropAspectRatio.CUSTOM -> { customDialog = true; return }
            CropAspectRatio.AUTO -> {
                aspectRatio = CropAspectRatio.AUTO
                scope.launch { detectEdges() }
            }
            CropAspectRatio.ORIGINAL -> {
                aspectRatio = CropAspectRatio.ORIGINAL
                reset()
                hint = null
            }
            else -> {
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
        rotBitmap = BitmapUtil.rotate90(rotBitmap)
        reset()
    }

    fun rotateRight() {
        rotBitmap = BitmapUtil.rotate90(BitmapUtil.rotate90(BitmapUtil.rotate90(rotBitmap)))
        reset()
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
                    .pointerInput(boxSize, rotBitmap) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                            val pos = down.position

                            // Select the target at finger-down, not after Compose's
                            // drag detector has already crossed touch-slop.
                            var selectedCorner = -1
                            var bestCornerDistance = Float.POSITIVE_INFINITY
                            val cornerHitRadius = 48.dp.toPx()

                            for (i in 0..3) {
                                val distance = (corner(i) - pos).getDistance()
                                if (distance <= cornerHitRadius && distance < bestCornerDistance) {
                                    bestCornerDistance = distance
                                    selectedCorner = i
                                }
                            }

                            var selectedEdge = -1
                            if (selectedCorner < 0) {
                                val xs = FloatArray(4) { corner(it).x }
                                val ys = FloatArray(4) { corner(it).y }
                                selectedEdge = CropGeometry.findEdgeHit(xs, ys, pos.x, pos.y)

                                if (selectedEdge >= 0) {
                                    val a = selectedEdge
                                    val b = (selectedEdge + 1) % 4
                                    val m0x = (xs[a] + xs[b]) / 2f
                                    val m0y = (ys[a] + ys[b]) / 2f
                                    val n = CropGeometry.outwardUnitNormal(xs, ys, selectedEdge)
                                    edgeDrag = EdgeDrag(selectedEdge, xs, ys, m0x, m0y, n[0], n[1])
                                }
                            }

                            dragCorner = selectedCorner

                            // A tap anywhere on the image is a guided correction.
                            // If it lands on an existing edge, a real drag has priority.
                            // The old implementation returned immediately after waiting for
                            // the drag, so edgeDrag was NEVER applied; this is why the + edge
                            // handles in the screenshots appeared completely unresponsive.
                            if (selectedCorner < 0 && selectedEdge >= 0) {
                                val edgeFirstDrag = awaitDragOrCancellation(down.id)
                                if (edgeFirstDrag == null) {
                                    scope.launch { refineCornerAtViewPoint(pos.x, pos.y) }
                                } else {
                                    fun applyEdge(current: Offset) {
                                        val ed = edgeDrag ?: return
                                        val res = CropGeometry.applyEdgeTranslation(
                                            ed.xs0, ed.ys0, ed.edgeIndex,
                                            ed.m0x, ed.m0y, ed.nx, ed.ny,
                                            current.x, current.y
                                        )
                                        if (res.applied) {
                                            for (i in 0..3) setViewCorner(i, res.xs[i], res.ys[i])
                                        }
                                    }
                                    applyEdge(edgeFirstDrag.position)
                                    edgeFirstDrag.consume()
                                    drag(down.id) { change ->
                                        applyEdge(change.position)
                                        change.consume()
                                    }
                                }
                                dragCorner = -1
                                edgeDrag = null
                                for (i in 0..3) snapActive[i] = false
                                snapHighlight = -1
                                return@awaitEachGesture
                            }

                            if (selectedCorner < 0) {
                                val firstDrag = awaitDragOrCancellation(down.id)
                                if (firstDrag == null) {
                                    scope.launch { refineCornerAtViewPoint(pos.x, pos.y) }
                                }
                                dragCorner = -1
                                edgeDrag = null
                                return@awaitEachGesture
                            }

                            // A tap directly on a corner handle means "find the real
                            // corner here"; only movement starts manual corner dragging.
                            val cornerDrag = awaitDragOrCancellation(down.id)
                            if (cornerDrag == null) {
                                scope.launch { refineCornerAtViewPoint(pos.x, pos.y) }
                                dragCorner = -1
                                edgeDrag = null
                                for (i in 0..3) snapActive[i] = false
                                snapHighlight = -1
                                return@awaitEachGesture
                            }

                            fun applyCorner(current: Offset) {
                                val i = selectedCorner
                                val newX = current.x.coerceIn(fit.left, fit.right)
                                val newY = current.y.coerceIn(fit.top, fit.bottom)
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

                            applyCorner(cornerDrag.position)
                            cornerDrag.consume()

                            drag(down.id) { change ->
                                applyCorner(change.position)
                                change.consume()
                            }

                            dragCorner = -1
                                edgeDrag = null
                                for (i in 0..3) snapActive[i] = false
                                snapHighlight = -1
                                return@awaitEachGesture
                            }

                            val firstDrag = cornerDrag
                            if (firstDrag != null) {
                                fun applyDragPosition(current: Offset) {
                                    val ed = edgeDrag

                                    if (ed != null && selectedEdge >= 0) {
                                        val res = CropGeometry.applyEdgeTranslation(
                                            ed.xs0, ed.ys0, ed.edgeIndex,
                                            ed.m0x, ed.m0y, ed.nx, ed.ny,
                                            current.x, current.y
                                        )
                                        if (res.applied) {
                                            for (i in 0..3) setViewCorner(i, res.xs[i], res.ys[i])
                                        }
                                    } else if (selectedCorner >= 0) {
                                        val i = selectedCorner
                                        val newX = current.x.coerceIn(fit.left, fit.right)
                                        val newY = current.y.coerceIn(fit.top, fit.bottom)
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
                                }

                                applyDragPosition(firstDrag.position)
                                firstDrag.consume()

                                drag(down.id) { change ->
                                    applyDragPosition(change.position)
                                    change.consume()
                                }
                            }

                            dragCorner = -1
                            edgeDrag = null
                            for (i in 0..3) snapActive[i] = false
                            snapHighlight = -1
                        }
                    }
            ) {
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
