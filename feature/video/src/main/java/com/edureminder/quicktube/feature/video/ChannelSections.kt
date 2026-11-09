package com.edureminder.quicktube.feature.video

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edureminder.quicktube.core.common.Format
import com.edureminder.quicktube.core.designsystem.LocalQtExtras
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.designsystem.pressable
import com.edureminder.quicktube.core.model.ChannelTab
import com.edureminder.quicktube.core.model.ItemKind
import com.edureminder.quicktube.core.model.MediaItem
import com.edureminder.quicktube.core.ui.CornerLabel
import com.edureminder.quicktube.core.ui.FooterLoading
import com.edureminder.quicktube.core.ui.Thumbnail
import com.edureminder.quicktube.core.ui.subtitle

/** Text tabs that scroll sideways, the open one underlined in the brand colour (stays on top while scrolling). */
@Composable
internal fun ChannelTabs(sections: List<ChannelSection>, current: ChannelSection?, onSelect: (ChannelSection) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val brand = LocalQtExtras.current.brand
    Column(Modifier.fillMaxWidth().background(cs.surface)) {
        LazyRow(contentPadding = PaddingValues(horizontal = 8.dp)) {
            items(sections, key = { it.label }) { s ->
                val active = s == current
                val bar by animateDpAsState(if (active) 24.dp else 0.dp, label = "tab")
                Column(
                    Modifier.clip(RoundedCornerShape(12.dp)).pressable(scaleDown = 0.94f, onClick = { onSelect(s) })
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        s.label, style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Medium,
                        color = if (active) cs.onSurface else cs.onSurfaceVariant,
                    )
                    Box(Modifier.padding(top = 6.dp).width(bar).height(3.dp).clip(RoundedCornerShape(50)).background(brand))
                }
            }
        }
        HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.5f))
    }
}

/** Home: a shelf per tab (Videos, Shorts, Live, Playlists…), each with "See all". */
internal fun LazyListScope.channelHome(
    ui: ListPageUi, onOpen: (MediaItem) -> Unit, onDownload: (MediaItem) -> Unit, onSeeAll: (ChannelTab) -> Unit,
) {
    if (ui.shelves.isEmpty()) { item(key = "home-loading") { FooterLoading() }; return }
    ui.shelves.forEach { (tab, list) ->
        item(key = "shelf-${tab.name}") {
            Column(Modifier.padding(top = 14.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tab.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).pressable(scaleDown = 0.94f, onClick = { onSeeAll(tab) }).padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("See all", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Icon(QtIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    }
                }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(list, key = { it.url }) { item ->
                        if (tab == ChannelTab.SHORTS) ShortCard(item, Modifier.width(132.dp), { onOpen(item) }, { onDownload(item) })
                        else WideCard(item, { onOpen(item) })
                    }
                }
            }
        }
    }
}

/** Shorts two to a row, tall pictures like the YouTube app's Shorts tab. */
internal fun LazyListScope.shortsGrid(items: List<MediaItem>, onOpen: (MediaItem) -> Unit, onDownload: (MediaItem) -> Unit) {
    items(items.chunked(2), key = { row -> row.first().url }) { row ->
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { item -> ShortCard(item, Modifier.weight(1f), { onOpen(item) }, { onDownload(item) }) }
            if (row.size == 1) Box(Modifier.weight(1f))
        }
    }
}

@Composable
private fun ShortCard(item: MediaItem, modifier: Modifier, onClick: () -> Unit, onDownload: () -> Unit) {
    Column(modifier.pressable(scaleDown = 0.97f, role = null, onClick = onClick)) {
        Thumbnail(item.thumbnail, Modifier.fillMaxWidth().aspectRatio(9f / 16f), corner = 16) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))))
            Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                Text(item.title, color = Color.White, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (item.viewCount >= 0) Text(Format.views(item.viewCount), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelSmall)
            }
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(32.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.45f))
                    .pressable(scaleDown = 0.85f, onClick = onDownload),
                contentAlignment = Alignment.Center,
            ) { Icon(QtIcons.Download, "Download", tint = Color.White, modifier = Modifier.size(17.dp)) }
        }
    }
}

/** A video, live stream or playlist in a Home shelf: 16:9 picture with the title below. */
@Composable
private fun WideCard(item: MediaItem, onClick: () -> Unit) {
    Column(Modifier.width(232.dp).pressable(scaleDown = 0.97f, role = null, onClick = onClick)) {
        Thumbnail(item.thumbnail, Modifier.fillMaxWidth().aspectRatio(16f / 9f), corner = 14) {
            if (item.kind == ItemKind.PLAYLIST) CornerLabel(if (item.streamCount >= 0) "${item.streamCount} videos" else "Playlist")
            else CornerLabel(if (item.isLive) "LIVE" else Format.duration(item.durationSec), live = item.isLive)
        }
        Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(item.subtitle(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
}

/** About: the channel's description (selectable, links included) and its numbers. */
@Composable
internal fun ChannelAbout(ui: ListPageUi) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.padding(16.dp)) {
        Text("Description", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        SelectionContainer {
            Text(ui.description?.takeIf { it.isNotBlank() } ?: "This channel hasn't written a description.",
                style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
        ui.header?.subscriberCount?.takeIf { it >= 0 }?.let { n ->
            Row(Modifier.padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(QtIcons.Person, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Text("${Format.count(n)} subscribers", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
            }
        }
        Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(QtIcons.Public, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Text(ui.header?.url.orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp))
        }
    }
}
