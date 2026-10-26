package com.edureminder.quicktube.feature.home

import com.edureminder.quicktube.core.designsystem.LocalBottomBarHeight
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.edureminder.quicktube.core.common.Platform
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.designsystem.pressable
import com.edureminder.quicktube.core.ui.BrandTile

private val morePlatforms = listOf(Platform.YOUTUBE, Platform.WHATSAPP, Platform.TIKTOK, Platform.INSTAGRAM, Platform.FACEBOOK, Platform.TWITTER)

@Composable
internal fun MoreTab(actions: HomeActions) {
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 16.dp + LocalBottomBarHeight.current), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Download from your favourite apps", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(4.dp, 4.dp, 4.dp, 6.dp))
        }
        items(morePlatforms) { p ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f), RoundedCornerShape(22.dp))
                    .pressable(scaleDown = 0.97f, onClick = { actions.openHowTo(p.name) })
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrandTile(p.title, size = 56.dp)
                Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    Text(p.title, style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        Box(
                            Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                                .padding(horizontal = 12.dp, vertical = 5.dp),
                        ) { Text("How to start", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                    }
                }
                Icon(QtIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            }
        }
    }
}
