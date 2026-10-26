package com.edureminder.quicktube

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder
import com.edureminder.quicktube.core.data.LibraryRepository
import com.edureminder.quicktube.core.analytics.Analytics
import com.edureminder.quicktube.core.analytics.analyticsModule
import com.edureminder.quicktube.core.data.SettingsRepository
import com.edureminder.quicktube.core.data.dataModule
import com.edureminder.quicktube.core.extractor.NewPipeMediaExtractor
import com.edureminder.quicktube.core.extractor.detectCountry
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.edureminder.quicktube.core.download.DownloadManager
import com.edureminder.quicktube.core.download.downloadModule
import com.edureminder.quicktube.core.downloadsheet.downloadSheetModule
import com.edureminder.quicktube.core.media.mediaModule
import com.edureminder.quicktube.core.ytdlp.YtDlpExtractor
import com.edureminder.quicktube.core.ytdlp.ytDlpModule
import com.edureminder.quicktube.di.featureModules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class QuickTubeApplication : Application(), SingletonImageLoader.Factory {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@QuickTubeApplication)
            modules(analyticsModule, dataModule, downloadModule, mediaModule, downloadSheetModule, ytDlpModule)
            modules(featureModules)
        }
        get<DownloadManager>().start()
        appScope.launch {
            // Respect Settings → Privacy (crash reports + anonymous usage).
            val analytics = get<Analytics>()
            get<SettingsRepository>().settings.map { it.analyticsEnabled }.distinctUntilChanged().collect(analytics::setEnabled)
        }
        appScope.launch {
            val library = get<LibraryRepository>()
            library.purgeExpiredTrash()
            // Files already in the download folder (e.g. after a reinstall) show up in the library again.
            library.importFolder(get<SettingsRepository>().current().downloadDir)
        }
        appScope.launch {
            // Unpack the site engine in the background, then refresh its site support every few days.
            val ytdlp = get<YtDlpExtractor>()
            ytdlp.init()
            val prefs = getSharedPreferences("engine", MODE_PRIVATE)
            // Daily check (cheap: a conditional request that returns 304 when nothing changed).
            if (System.currentTimeMillis() - prefs.getLong("updated", 0) > 24L * 60 * 60 * 1000) {
                ytdlp.update()?.let { prefs.edit().putLong("updated", System.currentTimeMillis()).putString("version", it).apply() }
            }
        }
        appScope.launch {
            get<SettingsRepository>().settings.map { it.contentRegion }.distinctUntilChanged().collect { region ->
                val country = region.ifBlank { detectCountry(this@QuickTubeApplication) }
                get<NewPipeMediaExtractor>().setRegion(java.util.Locale.getDefault().language.ifBlank { "en" }, country)
            }
        }
    }

    override fun newImageLoader(context: PlatformContext) = ImageLoader.Builder(context)
        .components { add(VideoFrameDecoder.Factory()) }
        .crossfade(true)
        .build()
}
