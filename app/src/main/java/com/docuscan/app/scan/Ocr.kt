package com.docuscan.app.scan

import android.content.Context
import android.graphics.Bitmap
import cz.adaptech.tesseract4android.TessBaseAPI
import java.io.File
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * On-device, fully offline OCR (Tesseract 4 via tesseract4android).
 *
 * The English traineddata is bundled in `assets/tessdata/eng.traineddata`
 * and copied to private storage on first use (required on API 29+). OCR is
 * never run automatically - only when the user explicitly taps OCR in the
 * editor. Runs on a background thread.
 */
object Ocr {

    private const val LANG = "eng"
    private const val MAX_OCR_DIM = 1600
    private val lock = ReentrantLock()

    /** Ensures eng.traineddata exists in private storage; returns the file or null. */
    fun ensureTrainedData(context: Context): File? {
        val dir = File(context.filesDir, "tessdata")
        val dst = File(dir, "$LANG.traineddata")
        if (dst.exists() && dst.length() > 100_000) return dst
        dir.mkdirs()
        return try {
            context.assets.open("tessdata/$LANG.traineddata").use { input ->
                dst.outputStream().use { output -> input.copyTo(output) }
            }
            dst
        } catch (e: IOException) {
            null
        }
    }

    /** Recognizes text from [bitmap]. Returns null when nothing was recognized or OCR failed. */
    fun recognize(context: Context, bitmap: Bitmap): String? {
        val data = ensureTrainedData(context) ?: return null
        val dataPath = data.parentFile?.absolutePath ?: return null
        val scaled = BitmapUtil.fitMax(bitmap, MAX_OCR_DIM)
        return lock.withLock {
            try {
                val api = TessBaseAPI()
                try {
                    if (!api.init(dataPath, LANG)) return null
                    api.setImage(scaled)
                    api.getUTF8Text()?.trim()
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
}
