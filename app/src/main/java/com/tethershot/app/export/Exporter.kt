package com.tethershot.app.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/** Saves processed images to Pictures/TetherShot via MediaStore (no permissions needed on API 29+). */
class Exporter(private val context: Context) {

    @Throws(IOException::class)
    fun export(bitmap: Bitmap, baseName: String, quality: Int = 95): Uri {
        val fileName = "${baseName}_${System.currentTimeMillis()}.jpg"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/TetherShot")
            }
            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
            ) ?: throw IOException("Cannot create MediaStore entry")
            context.contentResolver.openOutputStream(uri)?.use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it)
            } ?: throw IOException("Cannot open output stream")
            uri
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "TetherShot"
            ).apply { mkdirs() }
            val file = File(dir, fileName)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
            Uri.fromFile(file)
        }
    }
}
