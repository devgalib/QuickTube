package com.edureminder.quicktube.feature.settings

import com.edureminder.quicktube.core.designsystem.LocalBottomBarHeight
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edureminder.quicktube.core.designsystem.LocalQtExtras
import com.edureminder.quicktube.core.designsystem.QtCard
import com.edureminder.quicktube.core.designsystem.icon.BrandIcons
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.designsystem.pressable
import com.edureminder.quicktube.core.model.ThemeMode
import org.koin.compose.viewmodel.koinViewModel

class SettingsActions(
    val downloadSettings: () -> Unit,
    val notificationSettings: () -> Unit,
    val theme: () -> Unit,
    val recycleBin: () -> Unit,
    val deletedHistory: () -> Unit,
    val statusSaver: () -> Unit,
    val vault: () -> Unit,
    val storageClean: () -> Unit,
    val largeFiles: () -> Unit,
    val appManager: () -> Unit,
    val region: () -> Unit,
    val about: () -> Unit,
)

private val Blue = Color(0xFF4C8DFF)
private val Orange = Color(0xFFFF8A3D)
private val Purple = Color(0xFF9B7BFF)
private val Green = Color(0xFF2FCB7A)
private val Pink = Color(0xFFFF5C93)
private val Teal = Color(0xFF1FC8C8)
private val Amber = Color(0xFFFFB020)

@Composable
fun SettingsScreen(actions: SettingsActions) {
    val vm = koinViewModel<SettingsViewModel>()
    val s by vm.settings.collectAsStateWithLifecycle()
    val trash by vm.trashCount.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val updates = org.koin.compose.koinInject<com.edureminder.quicktube.core.update.UpdateManager>()
    val updateState by updates.state.collectAsStateWithLifecycle()
    val updateSummary = when (updateState) {
        is com.edureminder.quicktube.core.update.UpdateState.Checking -> "Checking…"
        is com.edureminder.quicktube.core.update.UpdateState.UpToDate -> "You're on the latest version"
        is com.edureminder.quicktube.core.update.UpdateState.Failed -> "Couldn't check. Tap to try again"
        else -> "Version ${appVersion(context)}"
    }
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp + LocalBottomBarHeight.current)) {
        item {
            Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 24.dp, top = 18.dp, bottom = 6.dp))
        }
        item { AppearanceBanner(onClick = actions.theme) }
        item { GroupLabel("General") }
        item {
            QtCard {
                NavRow(QtIcons.Download, "Download settings", "Path, speed limit, tasks", tint = Blue, onClick = actions.downloadSettings)
                RowDivider()
                NavRow(QtIcons.Notifications, "Notification settings", tint = Orange, onClick = actions.notificationSettings)
                RowDivider()
                NavRow(QtIcons.Palette, "Appearance", trailing = when (s.themeMode) {
                    ThemeMode.SYSTEM -> "System"; ThemeMode.LIGHT -> "Light"; ThemeMode.DARK -> "Dark"
                }, tint = Purple, onClick = actions.theme)
                RowDivider()
                NavRow(QtIcons.Public, "Content region", trailing = s.contentRegion.ifBlank { "Auto" }, tint = Teal, onClick = actions.region)
            }
        }
        item { GroupLabel("Download tools") }
        item {
            QtCard {
                NavRow(QtIcons.RestoreFromTrash, "Recover deleted files", trailing = if (trash > 0) "$trash" else null, tint = Green, onClick = actions.recycleBin)
                RowDivider()
                NavRow(QtIcons.History, "Deleted download history", "Download again anything you removed", tint = Blue, onClick = actions.deletedHistory)
                RowDivider()
                NavRow(BrandIcons.WhatsApp, "WhatsApp status saver", tint = Color(0xFF25D366), onClick = actions.statusSaver)
                RowDivider()
                NavRow(QtIcons.Lock, "Vault", "Hide private files behind a PIN", tint = Pink, onClick = actions.vault)
            }
        }
        item { GroupLabel("Phone clean") }
        item {
            QtCard {
                NavRow(QtIcons.CleaningServices, "Storage clean", "Temporary files, cache and leftovers", tint = Teal, onClick = actions.storageClean)
                RowDivider()
                NavRow(QtIcons.FolderZip, "Large files clean", "Find big videos, music and photos", tint = Amber, onClick = actions.largeFiles)
                RowDivider()
                NavRow(QtIcons.Apps, "App uninstaller", tint = Purple, onClick = actions.appManager)
            }
        }
        item { GroupLabel("Privacy") }
        item {
            QtCard {
                SwitchRow(
                    "Share crash reports & usage", "Anonymous: never URLs, titles, searches or files. See PRIVACY.md",
                    s.analyticsEnabled, QtIcons.Shield,
                ) { v -> vm.update { it.copy(analyticsEnabled = v) } }
            }
        }
        item { GroupLabel("Info") }
        item {
            QtCard {
                NavRow(QtIcons.Feedback, "Feedback", tint = Orange) {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
                        .putExtra(Intent.EXTRA_SUBJECT, "QuickTube feedback"), "Send feedback"))
                }
                RowDivider()
                NavRow(QtIcons.Share, "Share QuickTube", tint = Green) {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(
                        Intent.EXTRA_TEXT, "Download videos and music with QuickTube: https://play.google.com/store/apps/details?id=${context.packageName}",
                    ), "Share QuickTube"))
                }
                RowDivider()
                NavRow(QtIcons.Downloading, "Check for updates", updateSummary, tint = Teal) { updates.check(userInitiated = true) }
                RowDivider()
                NavRow(QtIcons.Info, "About", trailing = "v" + appVersion(context), tint = Blue, onClick = actions.about)
            }
        }
    }
}

@Composable
private fun AppearanceBanner(onClick: () -> Unit) {
    val ex = LocalQtExtras.current
    Row(
        Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(ex.brand)
            .pressable(scaleDown = 0.98f, onClick = onClick).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Make it yours", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimary)
            Text("Pick a colour theme, dark or light", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f))
        }
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
            Icon(QtIcons.Palette, null, tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

fun appVersion(context: android.content.Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
