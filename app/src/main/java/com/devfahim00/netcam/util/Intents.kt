package com.devfahim00.netcam.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import com.devfahim00.netcam.R

/** Opens this app's page in system settings (for permanently denied permissions). */
fun Context.openAppSettings() {
    try {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
        )
    } catch (_: ActivityNotFoundException) {
    }
}

/** Opens the gallery, showing the latest captured photo when available. */
fun Context.openGallery(latest: Uri?) {
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                latest ?: MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                "image/*"
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, R.string.no_gallery_app, Toast.LENGTH_SHORT).show()
    }
}
