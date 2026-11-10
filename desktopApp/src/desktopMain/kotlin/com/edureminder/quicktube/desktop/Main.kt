package com.edureminder.quicktube.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.edureminder.quicktube.core.analytics.analyticsModule
import com.edureminder.quicktube.core.common.DesktopDirs
import com.edureminder.quicktube.core.data.LibraryRepository
import com.edureminder.quicktube.core.data.SettingsRepository
import com.edureminder.quicktube.core.data.dataModule
import com.edureminder.quicktube.core.download.DownloadManager
import com.edureminder.quicktube.core.download.downloadModule
import com.edureminder.quicktube.core.downloadsheet.BulkDownloader
import com.edureminder.quicktube.core.downloadsheet.downloadSheetModule
import com.edureminder.quicktube.core.extractor.NewPipeMediaExtractor
import com.edureminder.quicktube.core.ytdlp.DesktopYtDlpExtractor
import com.edureminder.quicktube.core.ytdlp.ytDlpModule
import com.edureminder.quicktube.desktop.player.DesktopPlayer
import com.edureminder.quicktube.feature.home.homeModule
import com.edureminder.quicktube.feature.search.searchModule
import com.edureminder.quicktube.feature.video.videoListsModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import org.koin.core.context.startKoin
import org.koin.mp.KoinPlatform
import java.io.File
import java.util.Locale
import java.util.prefs.Preferences

/** Full screen on/off for the window (the player's button, double-click, F, Esc). */
class WindowControls(val isFullscreen: () -> Boolean, val setFullscreen: (Boolean) -> Unit)

val LocalWindowControls = compositionLocalOf { WindowControls({ false }, {}) }

fun main() {
    // Smooth fonts and the right scale on HiDPI Linux screens.
    System.setProperty("awt.useSystemAAFontSettings", "on")
    System.setProperty("skiko.renderApi", System.getProperty("skiko.renderApi") ?: "OPENGL")
    startKoin {
        modules(analyticsModule, dataModule, downloadModule, downloadSheetModule, ytDlpModule, homeModule, searchModule, videoListsModule, desktopModule)
    }
    startBackgroundWork()
    // QT_SNAPSHOT=<file.png>: render the app off-screen at QT_SIZE for QT_SNAPSHOT_SECONDS and save a picture
    // (layout checks at many window sizes without opening or capturing any window).
    System.getenv("QT_SNAPSHOT")?.let { snapshot(File(it)); return }
    application {
        // QT_SIZE=WIDTHxHEIGHT opens the window at that size (testing narrow and wide layouts).
        val size = System.getenv("QT_SIZE")?.split('x')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 }
        val state = rememberWindowState(size = size?.let { DpSize(it[0].dp, it[1].dp) } ?: DpSize(1280.dp, 820.dp))
        var placementBeforeFull by remember { mutableStateOf(WindowPlacement.Floating) }
        val controls = remember(state) {
            WindowControls(
                isFullscreen = { state.placement == WindowPlacement.Fullscreen },
                setFullscreen = { on ->
                    if (on && state.placement != WindowPlacement.Fullscreen) { placementBeforeFull = state.placement; state.placement = WindowPlacement.Fullscreen }
                    if (!on && state.placement == WindowPlacement.Fullscreen) state.placement = placementBeforeFull
                },
            )
        }
        val shell = remember { ShellState() }
        Window(
            onCloseRequest = {
                KoinPlatform.getKoin().get<DesktopPlayer>().release()
                exitApplication()
            },
            state = state,
            title = "QuickTube",
            icon = painterResource("quicktube.png"),
            onPreviewKeyEvent = { e ->
                when {
                    e.type != KeyEventType.KeyDown -> false
                    e.key == Key.Escape -> when {
                        controls.isFullscreen() -> { controls.setFullscreen(false); true }
                        else -> shell.back()
                    }
                    e.key == Key.F11 -> { controls.setFullscreen(!controls.isFullscreen()); true }
                    e.isCtrlPressed && e.key == Key.L -> { shell.focusSearch(); true }
                    else -> false
                }
            },
        ) {
            window.minimumSize = java.awt.Dimension(360, 560)
            setSingletonImageLoaderFactory { ctx ->
                ImageLoader.Builder(ctx)
                    .components { add(OkHttpNetworkFetcherFactory(callFactory = { KoinPlatform.getKoin().get<OkHttpClient>() })) }
                    .memoryCache { MemoryCache.Builder().maxSizePercent(ctx, 0.2).build() }
                    .diskCache { DiskCache.Builder().directory(File(DesktopDirs.data, "image-cache").toOkioPath()).maxSizeBytes(200L * 1024 * 1024).build() }
                    .build()
            }
            LaunchedEffect(Unit) { window.isVisible = true }
            CompositionLocalProvider(LocalWindowControls provides controls) {
                DesktopApp(shell)
            }
        }
    }
}

/** What the Android app does in its Application class: resume downloads, keep yt-dlp current, follow the region setting. */
private fun startBackgroundWork() {
    val koin = KoinPlatform.getKoin()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    koin.get<DownloadManager>().start()
    val settings = koin.get<SettingsRepository>()
    scope.launch {
        val s = settings.current()
        koin.get<LibraryRepository>().run {
            purgeExpiredTrash()
            importFolder(s.downloadDir.ifBlank { settings.defaultDownloadDir })
        }
    }
    koin.get<BulkDownloader>()
    scope.launch {
        // yt-dlp changes every few days as sites change; check once a day.
        val prefs = Preferences.userRoot().node("com/edureminder/quicktube/engine")
        if (System.currentTimeMillis() - prefs.getLong("updated_at", 0) > 24 * 3600_000L) {
            koin.get<DesktopYtDlpExtractor>().update()?.let { prefs.putLong("updated_at", System.currentTimeMillis()) }
        }
    }
    scope.launch {
        settings.settings.map { it.contentRegion }.distinctUntilChanged().collect { region ->
            val country = region.ifBlank { Locale.getDefault().country.ifBlank { "US" } }
            koin.get<NewPipeMediaExtractor>().setRegion(Locale.getDefault().language.ifBlank { "en" }, country)
        }
    }
}

private fun snapshot(out: File) {
    val size = System.getenv("QT_SIZE")?.split('x')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 } ?: listOf(1280, 820)
    val seconds = System.getenv("QT_SNAPSHOT_SECONDS")?.toIntOrNull() ?: 10
    kotlinx.coroutines.runBlocking(Dispatchers.Main) {
        val scene = androidx.compose.ui.ImageComposeScene(size[0], size[1], androidx.compose.ui.unit.Density(1f)) {
            DesktopApp(remember { ShellState() })
        }
        var image: org.jetbrains.skia.Image? = null
        val end = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < end) {
            image = scene.render(System.nanoTime())
            kotlinx.coroutines.delay(100)
        }
        image = scene.render(System.nanoTime())
        out.writeBytes(image.encodeToData()!!.bytes)
        scene.close()
    }
    KoinPlatform.getKoin().get<DesktopPlayer>().release()
    kotlin.system.exitProcess(0)
}
