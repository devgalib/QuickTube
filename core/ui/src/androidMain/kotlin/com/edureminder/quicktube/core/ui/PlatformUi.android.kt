package com.edureminder.quicktube.core.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import com.edureminder.quicktube.core.common.Permissions

@Composable
actual fun rememberToaster(): Toaster {
    val context = LocalContext.current
    return remember(context) { Toaster { message, long -> Toast.makeText(context, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show() } }
}

@Composable
actual fun rememberStorageAccess(): StorageAccess {
    val context = LocalContext.current
    val pending = remember { arrayOfNulls<(Boolean) -> Unit>(1) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        pending[0]?.invoke(result.values.all { it })
        pending[0] = null
    }
    return remember(context, launcher) {
        StorageAccess(
            granted = { Permissions.granted(context, Permissions.storageWrite) },
            request = { onResult -> pending[0] = onResult; launcher.launch(Permissions.storageWrite) },
        )
    }
}

actual val isMobilePlatform: Boolean = true

@Composable
actual fun QtBackHandler(enabled: Boolean, onBack: () -> Unit) = androidx.activity.compose.BackHandler(enabled, onBack)

@Composable
actual fun rememberClipboardReader(): () -> String? {
    val context = LocalContext.current
    return remember(context) {
        {
            runCatching {
                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
            }.getOrNull()
        }
    }
}

@Composable
actual fun rememberTextFilePicker(onPicked: (PickedText) -> Unit): () -> Unit {
    val context = LocalContext.current
    val current = androidx.compose.runtime.rememberUpdatedState(onPicked)
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: android.net.Uri? ->
        if (uri != null) scope.launch {
            val text = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull()
            }
            current.value(PickedText(uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.').orEmpty(), text))
        }
    }
    return { launcher.launch(arrayOf("text/*")) }
}

@Composable
actual fun rememberTextSharer(): (subject: String, text: String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { subject, text ->
            val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                .putExtra(android.content.Intent.EXTRA_SUBJECT, subject).putExtra(android.content.Intent.EXTRA_TEXT, text)
            context.startActivity(android.content.Intent.createChooser(send, "Share"))
        }
    }
}
