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

/** Installs an APK through a PackageInstaller session (works on every Android version, no file sharing needed). */
internal object UpdateInstaller {
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
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
            val intent = Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName)
            // The system adds the result extras, so the PendingIntent must be mutable on Android 12+.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
    }
}

/** Receives the installer's result: shows the system "Install" confirmation, or reports failures. */
class UpdateInstallReceiver : BroadcastReceiver(), KoinComponent {
    private val manager: UpdateManager by inject()

    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // The new version replaces this process.
            PackageInstaller.STATUS_FAILURE_ABORTED -> manager.onInstallFailed("Update cancelled")
            else -> manager.onInstallFailed(
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.takeIf { it.isNotBlank() }
                    ?: "Couldn't install the update (code $status)",
            )
        }
    }
}

/** Android tells the new version it replaced the old one: clear the downloaded update file straight away. */
class UpdateCleanupReceiver : BroadcastReceiver(), KoinComponent {
    private val manager: UpdateManager by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) manager.cleanUpInstalledUpdates()
    }
}
