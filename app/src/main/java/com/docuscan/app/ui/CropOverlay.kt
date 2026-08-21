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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
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
import kotlin.math.abs

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
    var dragIndex by remember { mutableIntStateOf(-1) }

    fun corner(i: Int): Offset {
        val f = fit
        return Offset(f.left + norm[i * 2] * f.width, f.top + norm[i * 2 + 1] * f.height)
    }

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
                            var best = -1
                            var bestD = 90f
                            for (i in 0..3) {
                                val d = (corner(i) - pos).getDistance()
                                if (d < bestD) {
                                    bestD = d
                                    best = i
                                }
                            }
                            dragIndex = best
                        },
                        onDrag = { change, _ ->
                            val i = dragIndex
                            if (i >= 0) {
                                val f = fit
                                norm[i * 2] = ((change.position.x - f.left) / f.width).coerceIn(0.01f, 0.99f)
                                norm[i * 2 + 1] = ((change.position.y - f.top) / f.height).coerceIn(0.01f, 0.99f)
                            }
                            change.consume()
                        },
                        onDragEnd = { dragIndex = -1 },
                        onDragCancel = { dragIndex = -1 }
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

            for (i in 0..3) {
                val c = corner(i)
                canvas.drawCircle(c.x, c.y, 14.dp.toPx(), Paint().apply { color = android.graphics.Color.WHITE })
                canvas.drawCircle(c.x, c.y, 9.dp.toPx(), Paint().apply { color = accentInt })
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
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
                    val p = corner(idx)
                    PointF(
                        ((p.x - f.left) / f.width) * bitmap.width,
                        ((p.y - f.top) / f.height) * bitmap.height
                    )
                }
                onApply(BitmapUtil.perspectiveWarp(bitmap, q))
            }) {
                Text("Apply", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun Color.toArgbCompat(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(),
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt()
)
