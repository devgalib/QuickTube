package com.edureminder.quicktube.core.datastore

import android.content.Context
import android.os.Environment
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.edureminder.quicktube.core.model.AppSettings
import com.edureminder.quicktube.core.model.ThemeAccent
import com.edureminder.quicktube.core.model.ThemeMode
import com.edureminder.quicktube.core.model.VideoEndMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.io.File

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

class SettingsDataSource(private val context: Context) {
    private object K {
        val theme = stringPreferencesKey("theme")
        val accent = stringPreferencesKey("accent")
        val amoled = booleanPreferencesKey("amoled")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val dir = stringPreferencesKey("download_dir")
        val wifiTasks = intPreferencesKey("max_wifi")
        val mobileTasks = intPreferencesKey("max_mobile")
        val speed = intPreferencesKey("speed_limit")
        val mobileData = booleanPreferencesKey("mobile_data")
        val nProgress = booleanPreferencesKey("n_progress")
        val nCompleted = booleanPreferencesKey("n_completed")
        val nRecommended = booleanPreferencesKey("n_recommended")
        val nTools = booleanPreferencesKey("n_tools")
        val toolbar = booleanPreferencesKey("toolbar")
        val pin = stringPreferencesKey("vault_pin")
        val language = stringPreferencesKey("language")
        val region = stringPreferencesKey("region")
        val playbackSpeed = floatPreferencesKey("playback_speed")
        val onboarded = booleanPreferencesKey("onboarded")
        val analytics = booleanPreferencesKey("analytics")
        val playHistory = booleanPreferencesKey("play_history")
        val resume = booleanPreferencesKey("resume_playback")
        val seekStep = intPreferencesKey("seek_step")
        val holdSpeed = floatPreferencesKey("hold_speed")
        val gBrightness = booleanPreferencesKey("g_brightness")
        val gVolume = booleanPreferencesKey("g_volume")
        val gSeek = booleanPreferencesKey("g_seek")
        val gHold = booleanPreferencesKey("g_hold")
        val gDoubleTap = booleanPreferencesKey("g_double_tap")
        val endMode = stringPreferencesKey("video_end_mode")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val autoSubtitles = booleanPreferencesKey("auto_subtitles")
        val saveSubtitles = booleanPreferencesKey("save_subtitles")
        val defaultQuality = stringPreferencesKey("default_quality")
        val sponsorBlock = booleanPreferencesKey("sponsor_block")
        val pinnedFolders = stringPreferencesKey("pinned_folders")
        val backgroundPlay = booleanPreferencesKey("background_play")
        val autoPip = booleanPreferencesKey("auto_pip")
        val autoplayNext = booleanPreferencesKey("autoplay_next")
        val showTrending = booleanPreferencesKey("show_trending")
        val captionStyle = stringPreferencesKey("caption_style")
    }

    val defaultDownloadDir: String
        get() = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "QuickTube").absolutePath

    val settings: Flow<AppSettings> = context.dataStore.data.map(::settingsFrom)

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val old = settingsFrom(p)
            val s = transform(old)
            p[K.theme] = s.themeMode.name
            p[K.accent] = s.accent.name
            p[K.amoled] = s.amoled
            p[K.dynamic] = s.dynamicColor
            p[K.dir] = s.downloadDir
            p[K.wifiTasks] = s.maxTasksWifi
            p[K.mobileTasks] = s.maxTasksMobile
            p[K.speed] = s.speedLimitKbps
            p[K.mobileData] = s.allowMobileData
            p[K.nProgress] = s.notifyProgress
            p[K.nCompleted] = s.notifyCompleted
            p[K.nRecommended] = s.notifyRecommended
            p[K.nTools] = s.notifyTools
            p[K.toolbar] = s.toolbarNotification
            if (s.vaultPin == null) p.remove(K.pin) else p[K.pin] = s.vaultPin!!
            p[K.language] = s.language
            p[K.region] = s.contentRegion
            p[K.playbackSpeed] = s.playbackSpeed
            p[K.onboarded] = s.onboarded
            p[K.analytics] = s.analyticsEnabled
            p[K.playHistory] = s.playHistory
            p[K.resume] = s.resumePlayback
            p[K.seekStep] = s.seekStepSec
            p[K.holdSpeed] = s.holdSpeed
            p[K.gBrightness] = s.gestureBrightness
            p[K.gVolume] = s.gestureVolume
            p[K.gSeek] = s.gestureSeek
            p[K.gHold] = s.gestureHoldSpeed
            p[K.gDoubleTap] = s.gestureDoubleTap
            p[K.endMode] = s.videoEndMode.name
            p[K.keepScreenOn] = s.keepScreenOn
            p[K.autoSubtitles] = s.autoSubtitles
            p[K.saveSubtitles] = s.saveSubtitles
            p[K.defaultQuality] = s.defaultQuality
            p[K.sponsorBlock] = s.sponsorBlock
            p[K.pinnedFolders] = s.pinnedFolders.joinToString(",")
            p[K.backgroundPlay] = s.backgroundPlay
            p[K.autoPip] = s.autoPip
            p[K.autoplayNext] = s.autoplayNext
            p[K.showTrending] = s.showTrending
            p[K.captionStyle] = s.captionStyle.encode()
        }
    }

    private fun settingsFrom(p: Preferences) = AppSettings(
        themeMode = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
        accent = p[K.accent]?.let { runCatching { ThemeAccent.valueOf(it) }.getOrNull() } ?: ThemeAccent.MINT,
        amoled = p[K.amoled] ?: false,
        dynamicColor = p[K.dynamic] ?: false,
        downloadDir = p[K.dir] ?: defaultDownloadDir,
        maxTasksWifi = p[K.wifiTasks] ?: 4,
        maxTasksMobile = p[K.mobileTasks] ?: 2,
        speedLimitKbps = p[K.speed] ?: 0,
        allowMobileData = p[K.mobileData] ?: true,
        notifyProgress = p[K.nProgress] ?: true,
        notifyCompleted = p[K.nCompleted] ?: true,
        notifyRecommended = p[K.nRecommended] ?: true,
        notifyTools = p[K.nTools] ?: true,
        toolbarNotification = p[K.toolbar] ?: false,
        vaultPin = p[K.pin],
        language = p[K.language] ?: "en",
        contentRegion = p[K.region] ?: "",
        playbackSpeed = p[K.playbackSpeed] ?: 1f,
        onboarded = p[K.onboarded] ?: false,
        analyticsEnabled = p[K.analytics] ?: true,
        playHistory = p[K.playHistory] ?: true,
        resumePlayback = p[K.resume] ?: true,
        seekStepSec = p[K.seekStep] ?: 10,
        holdSpeed = p[K.holdSpeed] ?: 2f,
        gestureBrightness = p[K.gBrightness] ?: true,
        gestureVolume = p[K.gVolume] ?: true,
        gestureSeek = p[K.gSeek] ?: true,
        gestureHoldSpeed = p[K.gHold] ?: true,
        gestureDoubleTap = p[K.gDoubleTap] ?: true,
        videoEndMode = p[K.endMode]?.let { runCatching { VideoEndMode.valueOf(it) }.getOrNull() } ?: VideoEndMode.REPEAT_ONE,
        keepScreenOn = p[K.keepScreenOn] ?: true,
        autoSubtitles = p[K.autoSubtitles] ?: true,
        saveSubtitles = p[K.saveSubtitles] ?: true,
        defaultQuality = p[K.defaultQuality] ?: "ASK",
        sponsorBlock = p[K.sponsorBlock] ?: true,
        pinnedFolders = p[K.pinnedFolders]?.split(',')?.mapNotNull { it.toLongOrNull() }.orEmpty(),
        backgroundPlay = p[K.backgroundPlay] ?: false,
        autoPip = p[K.autoPip] ?: true,
        autoplayNext = p[K.autoplayNext] ?: true,
        showTrending = p[K.showTrending] ?: false,
        captionStyle = com.edureminder.quicktube.core.model.CaptionStyle.decode(p[K.captionStyle]),
    )
}

val datastoreModule = module {
    single { SettingsDataSource(androidContext()) }
}
