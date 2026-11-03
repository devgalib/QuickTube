package com.edureminder.quicktube

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.lifecycleScope
import com.edureminder.quicktube.core.playerui.LocalPipMode
import com.edureminder.quicktube.core.playerui.PipState
import com.edureminder.quicktube.core.playerui.pipParams
import com.edureminder.quicktube.core.playerui.registerPipActions
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import com.edureminder.quicktube.core.common.Permissions
import com.edureminder.quicktube.core.common.Urls
import com.edureminder.quicktube.core.data.SettingsRepository
import com.edureminder.quicktube.core.downloadsheet.DownloadSheetController
import com.edureminder.quicktube.core.media.PlayerController
import com.edureminder.quicktube.core.model.AppSettings
import com.edureminder.quicktube.core.model.ThemeMode
import com.edureminder.quicktube.core.navigation.DownloadingRoute
import com.edureminder.quicktube.core.navigation.LibraryRoute
import com.edureminder.quicktube.core.navigation.PlayerRoute
import com.edureminder.quicktube.core.update.UpdateManager
import com.edureminder.quicktube.core.update.UpdatePrompt
import com.edureminder.quicktube.navigation.AppNavHost
import com.edureminder.quicktube.navigation.UrlRouter
import com.edureminder.quicktube.ui.AppTheme
import com.edureminder.quicktube.ui.isDark
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    private val settings: SettingsRepository by inject()
    private val router: UrlRouter by inject()
    private val sheet: DownloadSheetController by inject()
    private val player: PlayerController by inject()
    private val updates: UpdateManager by inject()
    private var pendingIntent by mutableStateOf<Intent?>(null)
    private var inPip by mutableStateOf(false)
    private var pipReceiver: android.content.BroadcastReceiver? = null
    private val pipListener = androidx.core.util.Consumer<androidx.core.app.PictureInPictureModeChangedInfo> {
        inPip = it.isInPictureInPictureMode
        // Closed (dragged to ✕ or the window's close button): Android stops the activity first, then ends PiP.
        if (!it.isInPictureInPictureMode && lifecycle.currentState == androidx.lifecycle.Lifecycle.State.CREATED) player.pause()
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestStartupPermissions()
        player.connect()
        // A video screen that's playing asks for picture-in-picture when the user leaves the app (Settings → Player).
        pipReceiver = registerPipActions(this, player)
        addOnPictureInPictureModeChangedListener(pipListener)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) lifecycleScope.launch {
            combine(PipState.wanted, PipState.ratio, player.state.map { it.isPlaying }) { want, ratio, playing -> Triple(want, ratio, playing) }
                .distinctUntilChanged()
                .collect { (want, ratio, playing) ->
                    // Never while the window is being closed (dragged to ✕): a new layout mid-animation leaves it stuck.
                    if (isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return@collect
                    runCatching { setPictureInPictureParams(pipParams(this@MainActivity, ratio, playing, autoEnter = want)) }
                }
        }
        // Look for a new GitHub release (throttled; release builds only unless the URL was overridden for testing).
        if (savedInstanceState == null) {
            updates.cleanUpInstalledUpdates()
            updates.consumeJustUpdated()?.let { v ->
                android.widget.Toast.makeText(this, "QuickTube updated to $v", android.widget.Toast.LENGTH_SHORT).show()
            }
            updates.checkOnLaunch(force = BuildConfig.UPDATE_URL_OVERRIDDEN)
        }
        if (savedInstanceState == null) pendingIntent = intent
        setContent {
            val s by settings.settings.collectAsStateWithLifecycle(AppSettings())
            val nav = rememberNavController()
            val dark = s.isDark()
            // Again after picture-in-picture and rotation too (full-screen video): both reset the bars' style.
            val orientation = androidx.compose.ui.platform.LocalConfiguration.current.orientation
            DisposableEffect(dark, inPip, orientation) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            AppTheme(s) {
                CompositionLocalProvider(LocalPipMode provides inPip) {
                    AppNavHost(nav)
                    if (!inPip) UpdatePrompt()
                }
            }
            LaunchedEffect(pendingIntent) {
                val i = pendingIntent ?: return@LaunchedEffect
                pendingIntent = null
                handleIntent(i) { route -> nav.navigate(route) { launchSingleTop = true } }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ enters on its own (auto-enter); older versions need asking.
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.O until Build.VERSION_CODES.S && PipState.wanted.value) {
            runCatching { enterPictureInPictureMode(pipParams(this, PipState.ratio.value, true, autoEnter = false)) }
        }
    }

    override fun onStop() {
        super.onStop()
        // Left the app from a video without picture-in-picture: pause it unless Settings → Player → Background play is on.
        if (!isInPictureInPictureMode && !isChangingConfigurations && PipState.videoOnScreen.value && !player.prefs.backgroundPlay) player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOnPictureInPictureModeChangedListener(pipListener)
        pipReceiver?.let(::unregisterReceiver)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingIntent = intent
    }

    private fun handleIntent(intent: Intent, navigate: (Any) -> Unit) {
        intent.getStringExtra("open")?.let { open ->
            when {
                open == "downloads" -> navigate(DownloadingRoute)
                open == "player" -> navigate(PlayerRoute)
                open.startsWith("play:") -> navigate(LibraryRoute)
            }
            return
        }
        val url = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> Urls.extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT))
            else -> null
        } ?: return
        // Shared text or the share popup's fallback means "download this"; a tapped YouTube link just opens it.
        val pasted = intent.action == Intent.ACTION_SEND || intent.getBooleanExtra(EXTRA_SHOW_SHEET, false)
        navigate(if (pasted) router.routeForPasted(url) else router.routeFor(url))
    }

    private fun requestStartupPermissions() {
        // Only what this Android version needs: notifications (13+) and storage (10 and older).
        val perms = (Permissions.notifications + Permissions.storageWrite).filterNot { Permissions.granted(this, arrayOf(it)) }
        if (perms.isNotEmpty()) permissionLauncher.launch(perms.toTypedArray())
    }

    companion object {
        const val EXTRA_SHOW_SHEET = "show_sheet"
    }
}
