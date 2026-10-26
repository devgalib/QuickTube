package com.edureminder.quicktube.core.common

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Which runtime permissions each Android version actually needs. */
object Permissions {
    /** Writing into the public Downloads folder needs a runtime permission only up to Android 10 (API 29). */
    val storageWrite: Array<String>
        get() = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE) else emptyArray()

    /** Reading other apps' files (WhatsApp statuses) by path: Android 10 and older. Android 11+ uses the folder picker. */
    val legacyRead: Array<String>
        get() = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE) else emptyArray()

    /** Reading media libraries (large files cleaner). */
    val mediaRead: Array<String>
        get() = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_IMAGES,
            )
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    val notifications: Array<String>
        get() = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()

    fun granted(context: Context, perms: Array<String>) =
        perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /** On Android 14 a user may grant only "selected photos/videos"; that still counts as usable access. */
    fun mediaUsable(context: Context): Boolean = granted(context, mediaRead) ||
        (Build.VERSION.SDK_INT >= 34 && granted(context, arrayOf(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)))
}
