package com.docuscan.app.scan

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object MediaSaver {

    fun saveJpg(context: Context, bmp: Bitmap, name: String): Uri? {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DocuScan")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null
            try {
                val os = context.contentResolver.openOutputStream(uri) ?: return null
                os.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
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
            try {
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            } catch (e: Exception) {
                return null
            }
            Uri.fromFile(f)
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
            try {
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
            try {
                src.copyTo(f, overwrite = true)
            } catch (e: Exception) {
                return null
            }
            Uri.fromFile(f)
        }
    }
}
