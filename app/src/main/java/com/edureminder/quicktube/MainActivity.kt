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
import com.edureminder.quicktube.core.playerui.PipState
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
import com.edureminder.quicktube.player.FloatingPlayerActivity
import com.edureminder.quicktube.core.navigation.VideoRoute
import androidx.navigation.NavDestination.Companion.hasRoute
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

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestStartupPermissions()
        player.connect()
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
            // Again after rotation too (full-screen video): it resets the bars' style.
            val orientation = androidx.compose.ui.platform.LocalConfiguration.current.orientation
            DisposableEffect(dark, orientation) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            AppTheme(s) {
                AppNavHost(nav)
                UpdatePrompt()
            }
            LaunchedEffect(pendingIntent) {
                val i = pendingIntent ?: return@LaunchedEffect
                pendingIntent = null
                // Expanded from the floating window: the video's page if it's still open, else the full player.
                if (i.getStringExtra("open") == "expand") {
                    val d = nav.currentDestination
                    if (d?.hasRoute(VideoRoute::class) != true && d?.hasRoute(PlayerRoute::class) != true) nav.navigate(PlayerRoute) { launchSingleTop = true }
                    return@LaunchedEffect
                }
                handleIntent(i) { route -> nav.navigate(route) { launchSingleTop = true } }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Leaving the app from a playing video (Settings → Player → Picture-in-picture): it goes on in the floating
        // window, a task of its own, so opening QuickTube again doesn't close it.
        if (PipState.wanted.value && !PipState.floating.value) FloatingPlayerActivity.start(this)
    }

    override fun onStop() {
        super.onStop()
        // Left the app from a video without picture-in-picture: pause it unless Settings → Player → Background play is on.
        if (!isChangingConfigurations && !PipState.floating.value && PipState.videoOnScreen.value && !player.prefs.backgroundPlay) player.pause()
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
