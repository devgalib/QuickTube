package com.edureminder.quicktube.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import com.edureminder.quicktube.core.common.Branding
import com.edureminder.quicktube.core.common.DesktopDirs
import com.edureminder.quicktube.core.common.Tools
import com.edureminder.quicktube.core.data.SettingsRepository
import com.edureminder.quicktube.core.designsystem.IconTile
import com.edureminder.quicktube.core.designsystem.QtCard
import com.edureminder.quicktube.core.designsystem.QtChip
import com.edureminder.quicktube.core.designsystem.accentPreview
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.designsystem.pressable
import com.edureminder.quicktube.core.model.AppSettings
import com.edureminder.quicktube.core.model.ThemeAccent
import com.edureminder.quicktube.core.model.ThemeMode
import com.edureminder.quicktube.core.ui.MaxWidth
import com.edureminder.quicktube.core.ui.rememberToaster
import com.edureminder.quicktube.core.ui.show
import com.edureminder.quicktube.core.ytdlp.DesktopYtDlpExtractor
import com.edureminder.quicktube.desktop.player.DesktopPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.File
import java.util.concurrent.TimeUnit
import javax.swing.JFileChooser

/** Settings for the desktop app, one comfortable-width column centred in the window. */
@Composable
fun DesktopSettingsScreen() {
    val repo = koinInject<SettingsRepository>()
    val s by repo.settings.collectAsState(AppSettings())
    val scope = rememberCoroutineScope()
    fun update(t: (AppSettings) -> AppSettings) { scope.launch { repo.update(t) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        MaxWidth(760.dp) {
            Column(Modifier.statusBarsPadding().padding(bottom = 32.dp)) {
                Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 24.dp, top = 18.dp, bottom = 8.dp))

                Group("Appearance")
                QtCard {
                    Column(Modifier.padding(16.dp)) {
                        Text("Theme", style = MaterialTheme.typography.titleSmall)
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark").forEach { (m, label) ->
                                QtChip(label, s.themeMode == m, { update { it.copy(themeMode = m) } })
                            }
                        }
                        Text("Accent", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 18.dp))
                        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            ThemeAccent.entries.forEach { a ->
                                val (c1, c2) = accentPreview(a)
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(
                                        Modifier.size(44.dp).clip(CircleShape).background(Brush.linearGradient(listOf(c1, c2)))
                                            .border(if (s.accent == a) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                            .pressable(onClick = { update { it.copy(accent = a) } }),
                                        contentAlignment = Alignment.Center,
                                    ) { if (s.accent == a) Icon(QtIcons.Check, null, tint = Color.White) }
                                    Text(a.label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                    }
                    Divider()
                    SwitchRow(QtIcons.DarkMode, "Pure black", "Black backgrounds in dark mode", s.amoled) { v -> update { it.copy(amoled = v) } }
                }

                Group("Downloads")
                QtCard {
                    val folder = s.downloadDir.ifBlank { repo.defaultDownloadDir }
                    NavRow(QtIcons.Folder, "Download folder", folder) {
                        chooseFolder(folder)?.let { dir -> update { it.copy(downloadDir = dir) } }
                    }
                    Divider()
                    Column(Modifier.padding(16.dp)) {
                        Text("Downloads at the same time", style = MaterialTheme.typography.titleSmall)
                        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(1, 2, 3, 4, 6, 8).forEach { n -> QtChip("$n", s.maxTasksWifi == n, { update { it.copy(maxTasksWifi = n, maxTasksMobile = n) } }) }
                        }
                        Text("Default quality", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 18.dp))
                        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("ASK" to "Ask (720p)", "P360" to "360p", "P720" to "720p", "P1080" to "1080p", "BEST" to "Best", "AUDIO" to "Audio", "MP3" to "MP3").forEach { (k, label) ->
                                QtChip(label, s.defaultQuality == k, { update { it.copy(defaultQuality = k) } })
                            }
                        }
                    }
                    Divider()
                    SwitchRow(QtIcons.Subtitles, "Save captions", "YouTube videos keep their subtitles beside them (.srt)", s.saveSubtitles) { v -> update { it.copy(saveSubtitles = v) } }
                }

                Group("Tools")
                ToolsCard()

                Group("About")
                QtCard {
                    val uri = LocalUriHandler.current
                    NavRow(QtIcons.Info, "QuickTube for desktop", "Version ${appVersion()} • ${System.getProperty("os.name")}") {}
                    Divider()
                    NavRow(QtIcons.Public, "Website", Branding.URL) { runCatching { uri.openUri(Branding.URL) } }
                    Divider()
                    NavRow(QtIcons.Folder, "App data", DesktopDirs.data.path) { openInFileManager(DesktopDirs.data) }
                }
            }
        }
    }
}

/** ffmpeg, VLC and yt-dlp: whether each is there, and yt-dlp's update button. */
@Composable
private fun ToolsCard() {
    val ytdlp = koinInject<DesktopYtDlpExtractor>()
    val player = koinInject<DesktopPlayer>()
    val toast = rememberToaster()
    val scope = rememberCoroutineScope()
    var ffmpeg by remember { mutableStateOf<String?>("…") }
    var ytVersion by remember { mutableStateOf<String?>("…") }
    var updating by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            ffmpeg = runCatching {
                val p = ProcessBuilder(Tools.ffmpeg, "-version").redirectErrorStream(true).start()
                val first = p.inputStream.bufferedReader().readLine()
                p.waitFor(5, TimeUnit.SECONDS)
                first?.substringAfter("version ")?.substringBefore(' ')
            }.getOrNull()
            ytVersion = ytdlp.version()
        }
    }
    QtCard {
        NavRow(QtIcons.Movie, "ffmpeg", ffmpeg?.let { "Installed ($it): merges, converts and cuts downloads" } ?: "Not found — install ffmpeg for HD downloads, MP3 and cuts") {}
        Divider()
        NavRow(QtIcons.PlayCircle, "VLC", if (player.available) "Installed: plays videos and music" else "Not found — install VLC to play videos in the app") {}
        Divider()
        NavRow(QtIcons.Sync, "yt-dlp", (ytVersion?.let { "Version $it" } ?: "Not downloaded yet") + if (updating) " • updating…" else " • click to update") {
            if (updating) return@NavRow
            updating = true
            scope.launch {
                val v = ytdlp.update()
                ytVersion = v ?: ytVersion
                updating = false
                toast.show(if (v != null) "yt-dlp is up to date ($v)" else "Couldn't update yt-dlp. Check your internet connection")
            }
        }
    }
}

private fun appVersion(): String = System.getProperty("jpackage.app-version") ?: "dev"

private fun chooseFolder(current: String): String? {
    val chooser = JFileChooser(File(current).takeIf { it.isDirectory } ?: File(System.getProperty("user.home"))).apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Choose where downloads are saved"
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile?.absolutePath else null
}

@Composable
private fun Group(text: String) = Text(
    text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.padding(start = 28.dp, top = 22.dp, bottom = 10.dp),
)

@Composable
private fun Divider() = HorizontalDivider(Modifier.padding(start = 72.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

@Composable
private fun NavRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).pressable(scaleDown = 0.99f, role = null, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).pressable(scaleDown = 0.99f, role = null, onClick = { onChange(!checked) }).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconTile(icon)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}
