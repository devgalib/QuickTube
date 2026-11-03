package com.edureminder.quicktube.core.update

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    /** Android 8+: the user must allow "install unknown apps" for QuickTube first. */
    data class NeedsPermission(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val bytes: Long, val total: Long) : UpdateState
    /** Handed to Android's installer; [needsTap] when Android waits for the user to confirm (its dialog, or our notification). */
    data class Installing(val info: UpdateInfo, val needsTap: Boolean = false) : UpdateState
    data class Failed(val info: UpdateInfo?, val message: String) : UpdateState
}

/**
 * In-app updates from GitHub Releases: check → download → verify (SHA-256 + same signing key) → install
 * through [android.content.pm.PackageInstaller]. Only the official package checks automatically.
 */
class UpdateManager(
    private val context: Context,
    client: OkHttpClient,
    private val releaseApiUrl: String,
) {
    private val http = client.newBuilder().followRedirects(true).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state
    private var job: Job? = null
    /** Android's "Install this update?" screen, kept while it's waiting for the user. */
    @Volatile private var confirmIntent: Intent? = null
    /** Activities of this app on screen (for showing Android's install screen now, or a notification instead). */
    @Volatile private var started = 0
    val appVisible get() = started > 0

    init {
        (context.applicationContext as? android.app.Application)?.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: android.app.Activity) { started++ }
            override fun onActivityStopped(a: android.app.Activity) { started = (started - 1).coerceAtLeast(0) }
            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
    }

    /** Whether the sheet should be shown (user hasn't dismissed this version for now). */
    private val _promptVisible = MutableStateFlow(false)
    val promptVisible: StateFlow<Boolean> = _promptVisible

    val currentVersionCode: Long get() = packageInfo(context.packageName)?.let(::versionCode) ?: 0
    val currentVersionName: String get() = packageInfo(context.packageName)?.versionName.orEmpty()

    private val isDebuggable get() = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** Called on app start: quiet, throttled, and skipped for debug builds (unless the URL is overridden for testing). */
    fun checkOnLaunch(force: Boolean = false) {
        if (isDebuggable && !force) return
        if (System.currentTimeMillis() - prefs.getLong(KEY_LAST_CHECK, 0) < CHECK_INTERVAL_MS && !force) {
            // Still remind about an update that was found recently (and not skipped).
            prefs.getString(KEY_CACHED, null)?.let(::parse)?.takeIf(::isWanted)?.let(::offer)
            return
        }
        check(userInitiated = false)
    }

    /** Settings → "Check for updates": shows the update in progress, or checks. */
    fun checkOrShow() {
        when (_state.value) {
            is UpdateState.Downloading, is UpdateState.Installing, is UpdateState.NeedsPermission, is UpdateState.Available -> _promptVisible.value = true
            else -> check(userInitiated = true)
        }
    }

    /** Settings → "Check for updates". */
    fun check(userInitiated: Boolean = true) {
        if (job?.isActive == true) return
        job = scope.launch {
            if (userInitiated) _state.value = UpdateState.Checking
            val json = runCatching {
                http.newCall(Request.Builder().url(releaseApiUrl).header("Accept", "application/vnd.github+json").build())
                    .execute().use { r -> if (r.isSuccessful) r.body.string() else null }
            }.getOrNull()
            prefs.edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
            val info = json?.let(::parse)
            if (json != null) prefs.edit().putString(KEY_CACHED, json).apply()
            when {
                info != null && info.versionCode > currentVersionCode && (userInitiated || isWanted(info)) -> offer(info)
                userInitiated && json == null -> _state.value = UpdateState.Failed(null, "Couldn't reach GitHub. Check your connection.")
                userInitiated -> _state.value = UpdateState.UpToDate
                else -> _state.value = UpdateState.Idle
            }
        }
    }

    private fun parse(json: String) = UpdateParsing.parseRelease(json, Build.SUPPORTED_ABIS.toList())

    /**
     * Deletes downloaded update APKs once that version (or a newer one) is installed, like the Play Store does.
     * Runs right after the update ([UpdateCleanupReceiver]) and on every start. Only touches the update cache:
     * downloads, history and settings live elsewhere and are kept.
     */
    fun cleanUpInstalledUpdates() {
        val installed = currentVersionCode
        File(context.cacheDir, "updates").listFiles()?.forEach { f ->
            val code = Regex("^QuickTube-(\\d+)\\.apk").find(f.name)?.groupValues?.get(1)?.toLongOrNull()
            if (code == null || code <= installed) f.delete()
        }
        // Forget a release we already have, so it's never offered again.
        prefs.getString(KEY_CACHED, null)?.let(::parse)?.takeIf { it.versionCode <= installed }?.let {
            prefs.edit().remove(KEY_CACHED).apply()
        }
    }

    /** The version name once, on the first start after an update (for an "Updated to …" note), else null. */
    fun consumeJustUpdated(): String? {
        val installed = currentVersionCode
        val last = prefs.getLong(KEY_LAST_RUN, 0)
        if (last == installed) return null
        prefs.edit().putLong(KEY_LAST_RUN, installed).apply()
        return currentVersionName.takeIf { last in 1 until installed }
    }

    private fun isWanted(info: UpdateInfo) =
        info.versionCode > currentVersionCode && info.versionCode != prefs.getLong(KEY_SKIPPED, -1)

    private fun offer(info: UpdateInfo) {
        _state.value = UpdateState.Available(info)
        _promptVisible.value = true
    }

    /** Closes the sheet. A download or install in progress goes on in the background (notification bar). */
    fun later() {
        _promptVisible.value = false
        val s = _state.value
        if (s !is UpdateState.Downloading && s !is UpdateState.Installing) _state.value = UpdateState.Idle
    }

    /** Stops the download; the update stays available. */
    fun cancel() {
        val info = when (val s = _state.value) { is UpdateState.Downloading -> s.info; is UpdateState.Installing -> s.info; else -> null }
        job?.cancel()
        prefs.edit().remove(KEY_RESUME).apply()
        if (info != null) _state.value = UpdateState.Available(info)
    }

    fun skip(info: UpdateInfo) {
        prefs.edit().putLong(KEY_SKIPPED, info.versionCode).apply()
        later()
    }

    fun dismissResult() { if (_state.value is UpdateState.UpToDate || _state.value is UpdateState.Failed) _state.value = UpdateState.Idle }

    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    /** Opens Android's "Install unknown apps" switch for QuickTube. */
    fun permissionIntent(): Intent =
        Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${context.packageName}"))

    /**
     * "Update now": download (in the background, progress in the notification bar), verify and hand to Android's
     * installer. Without the install permission it returns false and remembers the update, so it continues by itself
     * once the permission is granted, even if Android restarted the app meanwhile ([resumeIfPending]).
     */
    fun start(info: UpdateInfo): Boolean {
        _promptVisible.value = true
        if (!canInstall()) {
            prefs.edit().putLong(KEY_RESUME, info.versionCode).apply()
            _state.value = UpdateState.NeedsPermission(info)
            return false
        }
        prefs.edit().remove(KEY_RESUME).apply()
        if (job?.isActive == true) return true
        _state.value = UpdateState.Downloading(info, 0, info.apkSize)
        UpdateService.start(context)
        job = scope.launch {
            runCatching {
                val apk = download(info)
                verify(apk, info)
                _state.value = UpdateState.Installing(info)
                prefs.edit().putLong(KEY_INSTALL_STARTED, System.currentTimeMillis()).putString(KEY_INSTALL_VERSION, info.versionName).apply()
                val screen = UpdateInstaller.installerScreen(context, apk)
                // Writing the APK into an installer session takes seconds: never on the main thread (it froze the app).
                if (screen != null) onConfirmNeeded(screen) else UpdateInstaller.install(context, apk)
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "update failed", e)
                _state.value = UpdateState.Failed(info, e.message ?: "Update failed")
                _promptVisible.value = true
            }
        }
        return true
    }

    /**
     * On every return to the app: an update waiting for the install permission continues once it's granted. Android
     * often restarts the app when that permission changes, so the pending update is remembered on disk.
     */
    fun resumeIfPending() {
        val s = _state.value
        if (s is UpdateState.NeedsPermission) { if (canInstall()) start(s.info); return }
        val pending = prefs.getLong(KEY_RESUME, -1)
        if (pending <= 0 || job?.isActive == true) return
        val info = prefs.getString(KEY_CACHED, null)?.let(::parse)?.takeIf { it.versionCode == pending && it.versionCode > currentVersionCode }
        if (info == null) { prefs.edit().remove(KEY_RESUME).apply(); return }
        if (canInstall()) start(info) else { _state.value = UpdateState.NeedsPermission(info); _promptVisible.value = true }
    }

    /** Android's "Install this update?" screen is waiting: show it now when the app is on screen, else notify. */
    internal fun onConfirmNeeded(confirm: Intent) {
        val info = (_state.value as? UpdateState.Installing)?.info
        confirmIntent = confirm
        if (info != null) _state.value = UpdateState.Installing(info, needsTap = true)
        if (appVisible && runCatching { context.startActivity(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        UpdateNotifier.ready(context, info?.versionName.orEmpty(), Intent(confirm))
    }

    /** The sheet's "Install" button: shows Android's install screen again (e.g. after it was dismissed). */
    fun confirmInstall() {
        val c = confirmIntent ?: return
        runCatching { context.startActivity(Intent(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    internal fun onInstallFailed(message: String) {
        val info = (_state.value as? UpdateState.Installing)?.info
        confirmIntent = null
        prefs.edit().remove(KEY_INSTALL_STARTED).apply()
        UpdateNotifier.cancel(context, UpdateNotifier.ID_READY)
        _state.value = UpdateState.Failed(info, message)
        _promptVisible.value = true
    }

    /**
     * Runs in the new version right after it replaced this one (when the phone delivers it: some limit apps started
     * in the background). After an update started in the app, a "QuickTube updated · tap to open" notification is
     * left; Android's installer screen itself ends with an "Open" button.
     */
    internal fun onReplaced() {
        cleanUpInstalledUpdates()
        UpdateNotifier.cancel(context, UpdateNotifier.ID_READY)
        UpdateNotifier.cancel(context, UpdateNotifier.ID_PROGRESS)
        val at = prefs.getLong(KEY_INSTALL_STARTED, 0)
        prefs.edit().remove(KEY_INSTALL_STARTED).remove(KEY_RESUME).apply()
        if (System.currentTimeMillis() - at <= RELAUNCH_WINDOW_MS && !appVisible) UpdateNotifier.updated(context, currentVersionName)
    }

    /**
     * The installer's "done" (PackageInstaller session path), received by the new version with permission to open an
     * activity: QuickTube opens again by itself.
     */
    internal fun onInstalled() {
        onReplaced()
        UpdateNotifier.cancel(context, UpdateNotifier.ID_DONE)
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { launch ->
            runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { Log.w(TAG, "Couldn't reopen after the update: $it"); UpdateNotifier.updated(context, currentVersionName) }
        }
    }

    private suspend fun download(info: UpdateInfo): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.filter { !it.name.startsWith("QuickTube-${info.versionCode}") }?.forEach { it.delete() }
        val target = File(dir, "QuickTube-${info.versionCode}.apk")
        if (target.exists() && (info.apkSize <= 0 || target.length() == info.apkSize)) return target
        val part = File(dir, "QuickTube-${info.versionCode}.apk.part")
        var done = if (part.exists()) part.length() else 0L
        val req = Request.Builder().url(info.apkUrl).apply { if (done > 0) header("Range", "bytes=$done-") }.build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("Download failed (HTTP ${r.code})")
            if (r.code == 200) { done = 0; part.delete() }
            val total = if (info.apkSize > 0) info.apkSize else done + r.body.contentLength()
            _state.value = UpdateState.Downloading(info, done, total)
            r.body.byteStream().use { input ->
                java.io.FileOutputStream(part, done > 0).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var last = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - last > 150) { last = now; _state.value = UpdateState.Downloading(info, done, total) }
                    }
                }
            }
        }
        if (!part.renameTo(target)) error("Couldn't save the update")
        return target
    }

    /** Refuses anything that isn't byte-identical to the release or isn't signed with our own key. */
    private fun verify(apk: File, info: UpdateInfo) {
        info.sha256Url?.let { url ->
            val expected = http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (r.isSuccessful) UpdateParsing.parseSha256(r.body.string()) else null
            } ?: error("Couldn't verify the download")
            val actual = MessageDigest.getInstance("SHA-256").let { md ->
                apk.inputStream().use { input -> val b = ByteArray(64 * 1024); while (true) { val n = input.read(b); if (n < 0) break; md.update(b, 0, n) } }
                md.digest().joinToString("") { "%02x".format(it) }
            }
            if (actual != expected) { apk.delete(); error("The download is damaged. Please try again.") }
        }
        val archive = archiveInfo(apk) ?: run { apk.delete(); error("The downloaded file isn't a valid app") }
        if (archive.packageName != context.packageName) { apk.delete(); error("This update is for a different app") }
        if (versionCode(archive) <= currentVersionCode) { apk.delete(); error("This version is already installed") }
        val mine = signatures(packageInfo(context.packageName))
        val theirs = signatures(archive)
        if (mine.isEmpty() || mine != theirs) { apk.delete(); error("This update isn't signed by QuickTube. It was not installed.") }
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(pkg: String): PackageInfo? = runCatching {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        if (Build.VERSION.SDK_INT >= 33) context.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
        else context.packageManager.getPackageInfo(pkg, flags)
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun archiveInfo(apk: File): PackageInfo? {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        return if (Build.VERSION.SDK_INT >= 33) context.packageManager.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        else context.packageManager.getPackageArchiveInfo(apk.path, flags)
    }

    @Suppress("DEPRECATION")
    private fun signatures(p: PackageInfo?): Set<String> {
        p ?: return emptySet()
        val sigs = if (Build.VERSION.SDK_INT >= 28) p.signingInfo?.apkContentsSigners else p.signatures
        return sigs.orEmpty().map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    @Suppress("DEPRECATION")
    private fun versionCode(p: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) p.longVersionCode else p.versionCode.toLong()

    companion object {
        private const val TAG = "Update"
        private const val KEY_LAST_CHECK = "last_check"
        private const val KEY_SKIPPED = "skipped_version"
        private const val KEY_CACHED = "cached_release"
        private const val KEY_LAST_RUN = "last_run_version"
        private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
        /** Version waiting for the install permission (survives Android restarting the app). */
        private const val KEY_RESUME = "resume_version"
        /** When this app handed an update to the installer: the new version reopens the app only right after that. */
        private const val KEY_INSTALL_STARTED = "install_started"
        private const val KEY_INSTALL_VERSION = "install_version"
        private const val RELAUNCH_WINDOW_MS = 15 * 60 * 1000L
    }
}
