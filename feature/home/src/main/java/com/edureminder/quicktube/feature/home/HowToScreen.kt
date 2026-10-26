package com.edureminder.quicktube.feature.home

import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.edureminder.quicktube.core.common.Platform
import com.edureminder.quicktube.core.designsystem.PillButton
import com.edureminder.quicktube.core.designsystem.QtTopBar

@Composable
fun HowToScreen(platformName: String, onBack: () -> Unit, openInBrowser: (String) -> Unit, openStatusSaver: () -> Unit) {
    val platform = runCatching { Platform.valueOf(platformName) }.getOrDefault(Platform.YOUTUBE)
    val context = LocalContext.current
    val steps: List<Pair<ImageVector, String>> = if (platform == Platform.WHATSAPP) listOf(
        QtIcons.TouchApp to "Open WhatsApp and view the status you want to keep",
        QtIcons.Link to "Come back to QuickTube → Settings → WhatsApp status saver",
        QtIcons.Download to "Tap the download button on any status",
    ) else listOf(
        QtIcons.Share to "Open ${platform.title}, find a video and tap \"Share\"",
        QtIcons.MoreHoriz to "Tap \"More\" (or \"Copy link\")",
        QtIcons.Download to "Choose \"QuickTube\" in the share list — or paste the link into QuickTube's search box",
    )
    Scaffold(topBar = { QtTopBar("How to download", onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 20.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    com.edureminder.quicktube.core.ui.BrandTile(platform.title, size = 56.dp)
                    Column(Modifier.padding(start = 16.dp)) {
                        Text(platform.title, style = MaterialTheme.typography.titleLarge)
                        Text("3 easy steps", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                steps.forEachIndexed { i, (icon, text) ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 10.dp).clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                            Text("${i + 1}", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                        }
                        Text(text, Modifier.weight(1f).padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyLarge)
                        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            PillButton(
                text = if (platform == Platform.WHATSAPP) "Open status saver" else "Open ${platform.title}",
                onClick = {
                    if (platform == Platform.WHATSAPP) { openStatusSaver(); return@PillButton }
                    val launch = platform.packageName?.let { context.packageManager.getLaunchIntentForPackage(it) }
                    if (launch != null) context.startActivity(launch)
                    else if (platform.home.isNotBlank()) openInBrowser(platform.home)
                    else context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=${platform.packageName}")))
                },
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 16.dp),
            )
        }
    }
}
