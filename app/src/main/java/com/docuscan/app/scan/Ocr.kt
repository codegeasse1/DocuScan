package com.docuscan.app.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs

/** A single recognized word, with confidence and normalized bounds (0..1). */
data class Word(
    val text: String,
    val confidence: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val lineIndex: Int = 0
) {
    val midY: Float get() = (top + bottom) / 2f
    val midX: Float get() = (left + right) / 2f
}

/** Full-page OCR result: readable text plus the per-word data. */
data class OcrResult(val text: String, val words: List<Word>)

/**
 * On-device, fully offline OCR (Tesseract 4 via tesseract4android).
 *
 * English traineddata is bundled in `assets/tessdata/eng.traineddata` and
 * copied to private storage on first use (required on API 29+). Additional
 * language packs (`.traineddata` files, e.g. from tessdata_fast) can be
 * imported from the user's storage and are picked up automatically.
 *
 * OCR is never run automatically - only when the user explicitly opens the
 * OCR screen or taps Re-run. Runs on a background thread (reentrant-locked).
 */
object Ocr {

    const val DEFAULT_LANG = "eng"
    private const val MAX_OCR_DIM = 1600
    private const val MIN_LANG_FILE_BYTES = 100_000
    private val lock = ReentrantLock()

    fun tessdataDir(context: Context): File = File(context.filesDir, "tessdata")

    /** All traineddata language codes currently available on-device. */
    fun availableLanguages(context: Context): List<String> {
        val dir = tessdataDir(context)
        if (!dir.exists()) return listOf(DEFAULT_LANG)
        return dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".traineddata") }
            ?.mapNotNull { it.name.removeSuffix(".traineddata").takeIf { n -> n.matches(Regex("[a-zA-Z_+]+")) } }
            ?.distinct()
            ?.sorted()
            ?.ifEmpty { listOf(DEFAULT_LANG) }
            ?: listOf(DEFAULT_LANG)
    }

    fun hasTrainedData(context: Context, lang: String): Boolean {
        val f = File(tessdataDir(context), "$lang.traineddata")
        return f.exists() && f.length() > MIN_LANG_FILE_BYTES
    }

    /** Ensures the requested language is available; copies bundled eng if needed. */
    fun ensureTrainedData(context: Context, lang: String = DEFAULT_LANG): File? {
        val dir = tessdataDir(context)
        val dst = File(dir, "$lang.traineddata")
        if (dst.exists() && dst.length() > MIN_LANG_FILE_BYTES) return dst
        if (lang != DEFAULT_LANG) return null
        dir.mkdirs()
        return try {
            context.assets.open("tessdata/$lang.traineddata").use { input ->
                dst.outputStream().use { output -> input.copyTo(output) }
            }
            dst
        } catch (e: IOException) {
            null
        }
    }

    /** Copies a user-picked .traineddata into private storage; returns its language code. */
    fun importTrainedData(context: Context, uri: Uri): String? {
        val dir = tessdataDir(context).also { it.mkdirs() }
        return try {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: return null
            val code = name.removeSuffix(".traineddata")
            if (code.isBlank() || !code.matches(Regex("[a-zA-Z_]+"))) return null
            val dst = File(dir, "$code.traineddata")
            val ins = context.contentResolver.openInputStream(uri) ?: return null
            ins.use { input ->
                dst.outputStream().use { output -> input.copyTo(output) }
            }
            if (dst.length() < MIN_LANG_FILE_BYTES) {
                dst.delete()
                null
            } else code
        } catch (e: Exception) {
            null
        }
    }

    /** Recognizes a full page; returns text plus per-word boxes/confidences. */
    fun recognizeWithWords(context: Context, bitmap: Bitmap, lang: String = DEFAULT_LANG): OcrResult? {
        val data = ensureTrainedData(context, lang) ?: return null
        val scaled = BitmapUtil.fitMax(bitmap, MAX_OCR_DIM)
        return lock.withLock {
            try {
                val api = TessBaseAPI()
                try {
                    if (!api.init(data.parentFile?.absolutePath, lang)) return null
                    api.setImage(scaled)
                    val words = mutableListOf<Word>()
                    val it = api.resultIterator
                    it.begin()
                    while (true) {
                        val text = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)?.trim().orEmpty()
                        if (text.isNotEmpty() && text.any { c -> c.isLetterOrDigit() }) {
                            val conf = it.confidence(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                            val rect = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                            if (rect != null && rect.width() > 0 && rect.height() > 0) {
                                words.add(
                                    Word(
                                        text = text,
                                        confidence = conf,
                                        left = rect.left / scaled.width.toFloat(),
                                        top = rect.top / scaled.height.toFloat(),
                                        right = rect.right / scaled.width.toFloat(),
                                        bottom = rect.bottom / scaled.height.toFloat()
                                    )
                                )
                            }
                        }
                        if (!it.next(TessBaseAPI.PageIteratorLevel.RIL_WORD)) break
                    }
                    it.delete()
                    if (words.isEmpty()) return null
                    val withLines = assignLines(words)
                    return OcrResult(buildText(withLines), withLines)
                } finally {
                    api.recycle()
                }
            } catch (e: Exception) {
                null
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
        }
    }

    /** Re-runs OCR on a single word region (normalized bounds), e.g. for re-OCR. */
    fun recognizeRegion(context: Context, bitmap: Bitmap, norm: RectF, lang: String = DEFAULT_LANG): String? {
        val data = ensureTrainedData(context, lang) ?: return null
        val l = (norm.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val t = (norm.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val r = (norm.right * bitmap.width).toInt().coerceIn(l + 1, bitmap.width)
        val b = (norm.bottom * bitmap.height).toInt().coerceIn(t + 1, bitmap.height)
        val pad = 14
        val l2 = (l - pad).coerceIn(0, bitmap.width - 1)
        val t2 = (t - pad).coerceIn(0, bitmap.height - 1)
        val r2 = (r + pad).coerceIn(l2 + 1, bitmap.width)
        val b2 = (b + pad).coerceIn(t2 + 1, bitmap.height)
        if (r2 - l2 < 8 || b2 - t2 < 8) return null
        val crop = Bitmap.createBitmap(bitmap, l2, t2, r2 - l2, b2 - t2)
        val scale = (180f / crop.height).coerceAtLeast(2.5f)
        val scaled = Bitmap.createScaledBitmap(
            crop,
            (crop.width * scale).toInt().coerceAtLeast(1),
            (crop.height * scale).toInt().coerceAtLeast(1),
            true
        )
        if (scaled !== crop) crop.recycle()
        return lock.withLock {
            try {
                val api = TessBaseAPI()
                try {
                    if (!api.init(data.parentFile?.absolutePath, lang)) return null
                    api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_WORD)
                    api.setImage(scaled)
                    val text = api.utf8Text?.trim()
                    text?.takeIf { it.isNotEmpty() && it.any { c -> c.isLetterOrDigit() } }
                } finally {
                    api.recycle()
                }
            } catch (e: Exception) {
                null
            } finally {
                scaled.recycle()
            }
        }
    }

    /** Groups words into reading-order lines and rebuilds plain text. */
    fun buildText(words: List<Word>): String {
        if (words.isEmpty()) return ""
        val byLine = words.groupBy { it.lineIndex }
        val lines = byLine.keys.sorted().mapNotNull { line ->
            byLine[line]?.sortedBy { it.midX }?.joinToString(" ") { w ->
                if (w.text.isBlank()) " " else w.text
            }?.replace(Regex("\\s+"), " ")?.trim()
        }.filter { it.isNotEmpty() }
        return lines.joinToString("\n")
    }

    private fun assignLines(words: List<Word>): List<Word> {
        val sorted = words.sortedBy { it.top }
        var line = 0
        var lineY = -1f
        var lineH = 0f
        return sorted.map { w ->
            val mid = w.midY
            if (lineY < 0f || abs(mid - lineY) > lineH * 0.7f) {
                lineY = mid
                lineH = (w.bottom - w.top).coerceAtLeast(0.001f)
                line++
                w.copy(lineIndex = line - 1)
            } else {
                lineY = lineY * 0.6f + mid * 0.4f
                lineH = (lineH * 0.7f + (w.bottom - w.top) * 0.3f).coerceAtLeast(0.001f)
                w.copy(lineIndex = line - 1)
            }
        }
    }
}
