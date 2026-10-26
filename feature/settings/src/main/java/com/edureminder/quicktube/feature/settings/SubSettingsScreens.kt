package com.edureminder.quicktube.feature.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edureminder.quicktube.core.designsystem.BrandTitle
import com.edureminder.quicktube.core.designsystem.LocalQtExtras
import com.edureminder.quicktube.core.designsystem.QtCard
import com.edureminder.quicktube.core.designsystem.QtTopBar
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import android.os.Environment
import org.koin.compose.viewmodel.koinViewModel
import java.io.File
import java.util.Locale

private fun speedLabel(kbps: Int) = when {
    kbps <= 0 -> "Unlimited"
    kbps >= 1024 -> "${kbps / 1024} MB/s"
    else -> "$kbps KB/s"
}

private val speedSteps = listOf(0, 128, 256, 512, 1024, 2048, 5120)

@Composable
fun DownloadSettingsScreen(onBack: () -> Unit) {
    val vm = koinViewModel<SettingsViewModel>()
    val s by vm.settings.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<String?>(null) }
    Scaffold(topBar = { QtTopBar("Download settings", onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            GroupLabel("Storage")
            QtCard {
                NavRow(QtIcons.Folder, "Download path", s.downloadDir.removePrefix("/storage/emulated/0")) { dialog = "path" }
            }
            GroupLabel("Network")
            QtCard {
                NavRow(QtIcons.Downloading, "Max download tasks", "Wi-Fi: ${s.maxTasksWifi} tasks · Mobile data: ${s.maxTasksMobile} tasks") { dialog = "tasks" }
                RowDivider()
                NavRow(QtIcons.Speed, "Download speed limit", speedLabel(s.speedLimitKbps)) { dialog = "speed" }
                RowDivider()
                SwitchRow("Download via mobile data", "Media will be downloaded using cellular data", s.allowMobileData, QtIcons.SignalCellular) { v ->
                    vm.update { it.copy(allowMobileData = v) }
                }
            }
        }
    }
    when (dialog) {
        "path" -> {
            val options = listOf(
                vm.defaultDir,
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "QuickTube").absolutePath,
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "QuickTube").absolutePath,
                File(Environment.getExternalStorageDirectory(), "QuickTube").absolutePath,
            )
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Download path") },
                text = {
                    Column {
                        options.forEach { p -> RadioRow(p.removePrefix("/storage/emulated/0"), s.downloadDir == p) { vm.update { it.copy(downloadDir = p) }; dialog = null } }
                    }
                },
                confirmButton = { TextButton(onClick = { dialog = null }) { Text("Close") } },
            )
        }
        "tasks" -> {
            var wifi by remember { mutableFloatStateOf(s.maxTasksWifi.toFloat()) }
            var mobile by remember { mutableFloatStateOf(s.maxTasksMobile.toFloat()) }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Max download tasks") },
                text = {
                    Column {
                        Text("Wi-Fi: ${wifi.toInt()} tasks")
                        Slider(wifi, { wifi = it }, valueRange = 1f..8f, steps = 6)
                        Text("Mobile data: ${mobile.toInt()} tasks")
                        Slider(mobile, { mobile = it }, valueRange = 1f..8f, steps = 6)
                        Text("Notice: Turning up this number might slow down your phone.", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = { TextButton(onClick = { vm.update { it.copy(maxTasksWifi = wifi.toInt(), maxTasksMobile = mobile.toInt()) }; dialog = null }) { Text("OK") } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
            )
        }
        "speed" -> {
            var idx by remember { mutableFloatStateOf(speedSteps.indexOf(s.speedLimitKbps).coerceAtLeast(0).toFloat()) }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text("Download speed limit") },
                text = {
                    Column {
                        Text("If your phone gets slow while downloading, please reduce the limit.", style = MaterialTheme.typography.bodySmall)
                        Text(speedLabel(speedSteps[idx.toInt()]), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                        Slider(idx, { idx = it }, valueRange = 0f..(speedSteps.size - 1).toFloat(), steps = speedSteps.size - 2)
                    }
                },
                confirmButton = { TextButton(onClick = { vm.update { it.copy(speedLimitKbps = speedSteps[idx.toInt()]) }; dialog = null }) { Text("OK") } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
            )
        }
    }
}

@Composable
fun NotificationSettingsScreen(onBack: () -> Unit) {
    val vm = koinViewModel<SettingsViewModel>()
    val s by vm.settings.collectAsStateWithLifecycle()
    Scaffold(topBar = { QtTopBar("Notifications", onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            NotificationPermissionBanner()
            GroupLabel("Download notifications")
            QtCard {
                SwitchRow("Download progress", "Notify me of download progress", s.notifyProgress, QtIcons.Downloading) { v -> vm.update { it.copy(notifyProgress = v) } }
                RowDivider()
                SwitchRow("Download completed", "Notify me when download is complete", s.notifyCompleted, QtIcons.CheckCircle) { v -> vm.update { it.copy(notifyCompleted = v) } }
            }
            GroupLabel("Push notifications")
            QtCard {
                SwitchRow("Recommended content", "Notify me of videos and music I might like", s.notifyRecommended, QtIcons.Star) { v -> vm.update { it.copy(notifyRecommended = v) } }
                RowDivider()
                SwitchRow("Tool notifications", "Notify me when new tools are released", s.notifyTools, QtIcons.Bolt) { v -> vm.update { it.copy(notifyTools = v) } }
            }
        }
    }
}

@Composable
fun ThemeSettingsScreen(onBack: () -> Unit) = AppearanceScreen(onBack)

@Composable
fun RegionScreen(onBack: () -> Unit) {
    val vm = koinViewModel<SettingsViewModel>()
    val s by vm.settings.collectAsStateWithLifecycle()
    val countries = remember {
        listOf("") + Locale.getISOCountries().sortedBy { Locale("", it).displayCountry }
    }
    Scaffold(topBar = { QtTopBar("Content region", onBack) }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            item {
                Text("Trending and music feeds follow this region. Pull to refresh the feed after changing it.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }
            items(countries) { c ->
                RadioRow(if (c.isEmpty()) "Auto (SIM / network)" else Locale("", c).displayCountry, s.contentRegion == c) {
                    vm.update { it.copy(contentRegion = c) }
                }
            }
        }
    }
}

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    Scaffold(topBar = { QtTopBar("About", onBack) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            androidx.compose.foundation.layout.Box(
                Modifier.padding(top = 12.dp, bottom = 16.dp).size(84.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(26.dp))
                    .background(LocalQtExtras.current.brand),
                contentAlignment = Alignment.Center,
            ) { androidx.compose.material3.Icon(QtIcons.Download, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(44.dp)) }
            Text("QuickTube", style = BrandTitle.copy(brush = LocalQtExtras.current.brand))
            Text("Version ${appVersion(context)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Search, watch and download videos and music from YouTube and the web. " +
                    "Share any link to QuickTube to download without leaving the app you're in.",
                textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 24.dp),
            )
            Text("Open-source software", style = MaterialTheme.typography.titleSmall)
            Text(
                "NewPipe Extractor (GPLv3) · AndroidX Media3, Room, Navigation, DataStore (Apache 2.0) · Koin (Apache 2.0) · " +
                    "Coil (Apache 2.0) · OkHttp (Apache 2.0) · Kotlin Coroutines & Serialization (Apache 2.0)",
                textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Text(
                "Only download content you have the right to save. Respect the terms of the sites you use.",
                textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 24.dp),
            )
        }
    }
}


/** Shown on Android 13+ when notifications are blocked; opens the system screen to allow them. */
@Composable
fun NotificationPermissionBanner() {
    val context = LocalContext.current
    if (android.os.Build.VERSION.SDK_INT < 33) return
    var granted by remember { mutableStateOf(true) }
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        granted = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        onPauseOrDispose {}
    }
    if (granted) return
    androidx.compose.foundation.layout.Row(
        Modifier.padding(16.dp).fillMaxWidth().clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f)).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(QtIcons.Notifications, null, tint = MaterialTheme.colorScheme.error)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text("Notifications are off", style = MaterialTheme.typography.titleSmall)
            Text("Allow them to see download progress", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = {
            context.startActivity(
                android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        }) { Text("Allow") }
    }
}
