package com.docuscan.app.scan

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import java.io.File

object MediaSaver {

    fun saveJpg(context: Context, bmp: Bitmap, name: String, quality: Int = 92): Uri? {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DocuScan")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null
            return try {
                val os = context.contentResolver.openOutputStream(uri) ?: return null
                os.use { bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), it) }
                context.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null, null
                )
                uri
            } catch (e: Exception) {
                context.contentResolver.delete(uri, null, null)
                null
            }
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir, "DocuScan")
            dir.mkdirs()
            val f = File(dir, name)
            return try {
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), it) }
                Uri.fromFile(f)
            } catch (e: Exception) {
                null
            }
        }
    }

    fun savePdf(context: Context, src: File, name: String): Uri? {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DocuScan")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            return try {
                val os = context.contentResolver.openOutputStream(uri) ?: return null
                os.use { o -> src.inputStream().use { it.copyTo(o) } }
                context.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                    null, null
                )
                uri
            } catch (e: Exception) {
                context.contentResolver.delete(uri, null, null)
                null
            }
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir, "DocuScan")
            dir.mkdirs()
            val f = File(dir, name)
            return try {
                src.copyTo(f, overwrite = true)
                Uri.fromFile(f)
            } catch (e: Exception) {
                null
            }
        }
    }

    fun saveTxt(context: Context, text: String, name: String): Uri? {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/DocuScan")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            return try {
                val os = context.contentResolver.openOutputStream(uri) ?: return null
                os.use { it.write(text.toByteArray()) }
                context.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                    null, null
                )
                uri
            } catch (e: Exception) {
                context.contentResolver.delete(uri, null, null)
                null
            }
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir, "DocuScan")
            dir.mkdirs()
            val f = File(dir, name)
            return try {
                f.writeText(text)
                Uri.fromFile(f)
            } catch (e: Exception) {
                null
            }
        }
    }

    /** Writes into a user-chosen inbox folder (SAF tree); returns the created file's uri. */
    fun saveToInbox(context: Context, treeUri: Uri, name: String, bytes: () -> java.io.InputStream?): Uri? {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val dir = root.findFile("DocuScan") ?: root.createDirectory("DocuScan") ?: return null
        val existing = dir.findFile(name)
        val file = if (existing != null && existing.exists()) existing else dir.createFile("application/octet-stream", name)
            ?: return null
        return try {
            context.contentResolver.openOutputStream(file.uri)?.use { out ->
                bytes()?.use { it.copyTo(out) }
            }
            file.uri
        } catch (e: Exception) {
            null
        }
    }
}
