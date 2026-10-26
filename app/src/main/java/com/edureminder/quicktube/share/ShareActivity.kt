package com.edureminder.quicktube.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edureminder.quicktube.MainActivity
import com.edureminder.quicktube.core.common.Urls
import com.edureminder.quicktube.core.data.SettingsRepository
import com.edureminder.quicktube.ui.AppTheme
import com.edureminder.quicktube.core.downloadsheet.DownloadSheetController
import com.edureminder.quicktube.core.downloadsheet.DownloadSheetHost
import com.edureminder.quicktube.core.model.AppSettings
import com.edureminder.quicktube.core.model.ThemeMode
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject

/**
 * Target of "Share → QuickTube" from YouTube, Facebook, TikTok, etc.
 * Shows only the format picker over the current app; the download continues in the background.
 */
class ShareActivity : ComponentActivity() {
    private val sheet: DownloadSheetController by inject()
    private val settings: SettingsRepository by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!openFrom(intent)) return
        setContent {
            val s by settings.settings.collectAsStateWithLifecycle(AppSettings())
            val target by sheet.target.collectAsStateWithLifecycle()
            AppTheme(s) {
                DownloadSheetHost(onOpenInBrowser = ::openInApp)
            }
            // Sheet closed (downloaded or dismissed) → return to the app the user came from.
            LaunchedEffect(target) { if (target == null) finish() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFrom(intent)
    }

    private fun openFrom(intent: Intent): Boolean {
        val url = Urls.extractUrl(intent.getStringExtra(Intent.EXTRA_TEXT))
        if (url == null) {
            Toast.makeText(this, "No link found to download", Toast.LENGTH_SHORT).show()
            finish()
            return false
        }
        sheet.open(url)
        get<com.edureminder.quicktube.core.analytics.Analytics>().event(com.edureminder.quicktube.core.analytics.Events.SHARE_TO_DOWNLOAD)
        return true
    }

    fun openInApp(url: String) {
        startActivity(
            Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(android.net.Uri.parse(url))
                .putExtra(MainActivity.EXTRA_SHOW_SHEET, true),
        )
        finish()
    }
}
