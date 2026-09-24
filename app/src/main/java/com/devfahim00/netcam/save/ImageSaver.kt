package com.devfahim00.netcam.save

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves captured photos to the gallery (Pictures/NetCam). */
object ImageSaver {

    private const val FOLDER = "NetCam"

    fun saveBitmap(context: Context, bitmap: Bitmap, jpegQuality: Int = 96): Uri? {
        val name = newFileName()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveMediaStore(context, name) { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
            }
        } else {
            saveLegacy(context, name) { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, out)
            }
        }
    }

    private fun newFileName(): String =
        "NetCam_" + SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date()) + ".jpg"

    private fun saveMediaStore(
        context: Context,
        name: String,
        write: (OutputStream) -> Unit
    ): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + File.separator + FOLDER
            )
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        try {
            val stream = resolver.openOutputStream(uri)
            if (stream == null) {
                resolver.delete(uri, null, null)
                return null
            }
            stream.use { write(it) }
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            return null
        }
        val done = ContentValues().apply {
            put(MediaStore.Images.Media.IS_PENDING, 0)
        }
        resolver.update(uri, done, null, null)
        return uri
    }

    private fun saveLegacy(
        context: Context,
        name: String,
        write: (OutputStream) -> Unit
    ): Uri? {
        return try {
            val base = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                ?: return null
            val dir = File(base, FOLDER)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, name)
            FileOutputStream(file).use { write(it) }
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf("image/jpeg"),
                null
            )
            FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                file
            )
        } catch (t: Throwable) {
            null
        }
    }
}
