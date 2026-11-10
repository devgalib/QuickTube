package com.edureminder.quicktube.core.ui

import androidx.compose.runtime.Composable

/** A short message for the user: Android's Toast, a snackbar in the desktop window. */
fun interface Toaster {
    fun show(message: String, long: Boolean)
}

fun Toaster.show(message: String) = show(message, false)

@Composable
expect fun rememberToaster(): Toaster

/**
 * Permission to save into the public download folder. Only Android 10 and older ask for it; newer Android versions and
 * computers can always save there.
 */
class StorageAccess(val granted: () -> Boolean, val request: (onResult: (Boolean) -> Unit) -> Unit)

@Composable
expect fun rememberStorageAccess(): StorageAccess

/** True on phones and tablets; the desktop app hides phone-only things (gestures, PiP, mobile data, WhatsApp…). */
expect val isMobilePlatform: Boolean

/** Handles the system Back gesture/button while [enabled] (Android); Esc in the desktop window does the same. */
@Composable
expect fun QtBackHandler(enabled: Boolean = true, onBack: () -> Unit)

/** Reads the clipboard's text now (null when it holds none). */
@Composable
expect fun rememberClipboardReader(): () -> String?

/** A picked text file: its name (without the extension) and its text, or null when it couldn't be read. */
class PickedText(val name: String, val text: String?)

/** Opens the system's file picker for a text file; [onPicked] gets it (nothing when the user cancels). */
@Composable
expect fun rememberTextFilePicker(onPicked: (PickedText) -> Unit): () -> Unit
