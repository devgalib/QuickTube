package com.edureminder.quicktube.feature.library

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import com.edureminder.quicktube.core.model.DownloadTask
import java.io.File

object FileActions {
    private fun uri(context: Context, path: String) =
        FileProvider.getUriForFile(context, context.packageName + ".files", File(path))

    fun share(context: Context, tasks: List<DownloadTask>) {
        val files = tasks.filter { File(it.filePath).exists() }
        if (files.isEmpty()) { Toast.makeText(context, "File not found", Toast.LENGTH_SHORT).show(); return }
        val intent = if (files.size == 1) Intent(Intent.ACTION_SEND)
            .setType(files[0].mimeType)
            .putExtra(Intent.EXTRA_STREAM, uri(context, files[0].filePath))
        else Intent(Intent.ACTION_SEND_MULTIPLE)
            .setType("*/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files.map { uri(context, it.filePath) }))
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "Share"))
    }

    fun openWith(context: Context, task: DownloadTask) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri(context, task.filePath), task.mimeType)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
        }
    }
}
