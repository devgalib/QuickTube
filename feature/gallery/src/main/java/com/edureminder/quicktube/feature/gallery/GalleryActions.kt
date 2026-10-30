package com.edureminder.quicktube.feature.gallery

import android.app.RecoverableSecurityException
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import com.edureminder.quicktube.core.model.GalleryMedia

/** Hands media to other apps and asks the system to delete it. Nothing here touches files directly except on Android 9 and older. */
object GalleryActions {

    fun share(context: Context, items: List<GalleryMedia>) {
        if (items.isEmpty()) return
        val uris = items.map { it.uri }
        val type = when {
            items.all { it.isVideo } -> "video/*"
            items.none { it.isVideo } -> "image/*"
            else -> "*/*"
        }
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        send.setType(type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        start(context, Intent.createChooser(send, null))
    }

    fun edit(context: Context, media: GalleryMedia) = start(
        context,
        Intent.createChooser(
            Intent(Intent.ACTION_EDIT).setDataAndType(media.uri, media.mimeType.ifBlank { if (media.isVideo) "video/*" else "image/*" })
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION),
            null,
        ),
    )

    /** "Set as" wallpaper / contact photo, handled by the system's chooser. */
    fun setAs(context: Context, media: GalleryMedia) = start(
        context,
        Intent.createChooser(
            Intent(Intent.ACTION_ATTACH_DATA).setDataAndType(media.uri, media.mimeType.ifBlank { "image/*" })
                .putExtra("mimeType", media.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            null,
        ),
    )

    fun openWith(context: Context, media: GalleryMedia) = start(
        context,
        Intent.createChooser(
            Intent(Intent.ACTION_VIEW).setDataAndType(media.uri, media.mimeType.ifBlank { if (media.isVideo) "video/*" else "image/*" })
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            null,
        ),
    )

    /**
     * Android 11+: the system's own "Move to trash?" dialog (recoverable for 30 days), returned as an [IntentSender].
     * Android 10: deletes directly, or returns the system's permission prompt for files other apps created.
     * Android 9 and older: deletes directly (the caller has asked for storage permission and confirmed first).
     * Returns null when nothing more is needed.
     */
    fun deleteRequest(context: Context, items: List<GalleryMedia>): IntentSender? {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return MediaStore.createTrashRequest(resolver, items.map { it.uri }, true).intentSender
        }
        for (m in items) {
            try {
                resolver.delete(m.uri, null, null)
            } catch (e: SecurityException) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && e is RecoverableSecurityException) return e.userAction.actionIntent.intentSender
                throw e
            }
        }
        return null
    }

    /** The system shows its own confirmation on Android 11+; older versions need ours. */
    val needsOwnConfirm: Boolean get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.R

    private fun start(context: Context, intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No app can do this", Toast.LENGTH_SHORT).show()
        }
    }
}
