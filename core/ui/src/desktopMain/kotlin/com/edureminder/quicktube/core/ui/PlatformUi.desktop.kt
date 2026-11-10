package com.edureminder.quicktube.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.MutableSharedFlow

/** Messages for the desktop window's snackbar (shown by the app shell). */
object DesktopToasts {
    val messages = MutableSharedFlow<Pair<String, Boolean>>(extraBufferCapacity = 16)
}

@Composable
actual fun rememberToaster(): Toaster = remember { Toaster { message, long -> DesktopToasts.messages.tryEmit(message to long) } }

@Composable
actual fun rememberStorageAccess(): StorageAccess = remember { StorageAccess(granted = { true }, request = { it(true) }) }

actual val isMobilePlatform: Boolean = false

/** Back handlers of the screens on top, newest last; the desktop window sends Esc / mouse "back" to the last enabled one. */
object DesktopBack {
    internal val handlers = mutableListOf<androidx.compose.runtime.State<Pair<Boolean, () -> Unit>>>()

    /** True when a screen took the Back press. */
    fun dispatch(): Boolean {
        val h = synchronized(handlers) { handlers.lastOrNull { it.value.first } } ?: return false
        h.value.second()
        return true
    }
}

@Composable
actual fun QtBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val state = androidx.compose.runtime.rememberUpdatedState(enabled to onBack)
    androidx.compose.runtime.DisposableEffect(Unit) {
        synchronized(DesktopBack.handlers) { DesktopBack.handlers += state }
        onDispose { synchronized(DesktopBack.handlers) { DesktopBack.handlers -= state } }
    }
}

@Composable
actual fun rememberClipboardReader(): () -> String? = remember {
    {
        runCatching {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
        }.getOrNull()
    }
}

@Composable
actual fun rememberTextFilePicker(onPicked: (PickedText) -> Unit): () -> Unit {
    val current = androidx.compose.runtime.rememberUpdatedState(onPicked)
    return remember {
        {
            val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Open a list of links", java.awt.FileDialog.LOAD)
            dialog.setFilenameFilter { _, name -> name.endsWith(".txt", true) || name.endsWith(".csv", true) || '.' !in name }
            dialog.isVisible = true
            val file = dialog.files.firstOrNull()
            if (file != null) current.value(PickedText(file.nameWithoutExtension, runCatching { file.readText() }.getOrNull()))
        }
    }
}

@Composable
actual fun rememberTextSharer(): (subject: String, text: String) -> Unit = remember {
    { _, text ->
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(java.awt.datatransfer.StringSelection(text), null)
        DesktopToasts.messages.tryEmit("Copied — paste it anywhere to share" to false)
    }
}
