package com.edureminder.quicktube.core.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File

internal object UpdateInstaller {
    /**
     * Android's own installer screen for [apk] (shared through the app's FileProvider, `<applicationId>.files`). It
     * asks to confirm, shows the install while Android (and Play Protect) checks the update, and ends with an "Open"
     * button. Unlike a PackageInstaller session it depends on no broadcast, which some phones deliver minutes late (a
     * jammed background queue) or not at all. Null when no installer handles it (then [install] is used).
     */
    fun installerScreen(context: Context, apk: File): Intent? = runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            .takeIf { it.resolveActivity(context.packageManager) != null }
    }.getOrNull()

    /** Installs an APK through a PackageInstaller session: the fallback when there is no installer screen. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        // Earlier attempts that never got confirmed stay open in Android (and keep their copy of the APK): drop them.
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            // Android 12+: once QuickTube installed itself, later updates may go through without the extra tap.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            // Foreground priority: Android's answer ("needs the user to confirm") goes in the background broadcast queue
            // otherwise, which on some phones is thousands of broadcasts long, and the install screen never showed up.
            val intent = Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            // The system adds the result extras, so the PendingIntent must be mutable on Android 12+.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
            android.util.Log.i("Update", "Install session $id committed")
        }
    }
}

/** Receives the installer's result: shows the system "Install" confirmation, or reports failures. */
class UpdateInstallReceiver : BroadcastReceiver(), KoinComponent {
    private val manager: UpdateManager by inject()

    override fun onReceive(context: Context, intent: Intent) {
        android.util.Log.i("Update", "Installer status ${intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -99)}")
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.let(manager::onConfirmNeeded)
            }
            // Delivered to the new version (where Android does): open it again.
            PackageInstaller.STATUS_SUCCESS -> manager.onInstalled()
            PackageInstaller.STATUS_FAILURE_ABORTED -> manager.onInstallFailed("Update cancelled")
            else -> manager.onInstallFailed(
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.takeIf { it.isNotBlank() }
                    ?: "Couldn't install the update (code $status)",
            )
        }
    }
}

/** Android tells the new version it replaced the old one: clear the update file, and reopen the app after an in-app update. */
class UpdateCleanupReceiver : BroadcastReceiver(), KoinComponent {
    private val manager: UpdateManager by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) manager.onReplaced()
    }
}
