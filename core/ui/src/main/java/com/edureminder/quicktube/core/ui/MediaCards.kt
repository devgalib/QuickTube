package com.edureminder.quicktube.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.edureminder.quicktube.core.common.Format
import com.edureminder.quicktube.core.designsystem.QtIconButton
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.designsystem.pressable
import com.edureminder.quicktube.core.model.ItemKind
import com.edureminder.quicktube.core.model.MediaItem

@Composable
fun Thumbnail(
    url: String?,
    modifier: Modifier = Modifier,
    corner: Int = 14,
    overlay: @Composable BoxScope.() -> Unit = {},
) {
    Box(modifier.clip(RoundedCornerShape(corner.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        overlay()
    }
}

/** Small dark chip in the corner of a thumbnail (duration, LIVE, count). */
@Composable
fun BoxScope.CornerLabel(text: String, modifier: Modifier = Modifier, live: Boolean = false) {
    if (text.isBlank()) return
    Row(
        modifier.align(Alignment.BottomEnd).padding(7.dp).clip(RoundedCornerShape(6.dp))
            .background(if (live) Color(0xFFE5243B) else Color.Black.copy(alpha = 0.72f)).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (live) Box(Modifier.size(6.dp).clip(CircleShape).background(Color.White)).also { Spacer(Modifier.width(4.dp)) }
        Text(text, color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun Avatar(url: String?, size: Int = 36, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (url == null) Icon(QtIcons.Person, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size((size * 0.55f).dp))
        else AsyncImage(url, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

fun MediaItem.subtitle(): String = when (kind) {
    ItemKind.CHANNEL -> listOf(
        if (subscriberCount >= 0) "${Format.count(subscriberCount)} subscribers" else "",
        if (streamCount >= 0) "$streamCount videos" else "",
    ).filter { it.isNotBlank() }.joinToString(" • ")
    ItemKind.PLAYLIST -> listOf(uploader, if (streamCount >= 0) "$streamCount videos" else "").filter { it.isNotBlank() }.joinToString(" • ")
    ItemKind.VIDEO -> listOf(uploader, Format.views(viewCount), uploadDate.orEmpty()).filter { it.isNotBlank() }.joinToString(" • ")
}

/** Accent-tinted round download button used on every card. */
@Composable
fun DownloadIconButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    QtIconButton(
        QtIcons.Download, "Download", onClick, modifier,
        tint = MaterialTheme.colorScheme.primary,
        container = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        size = 40.dp, iconSize = 21.dp,
    )
}

/** Full-width feed card: large thumbnail, avatar, title and a quick download button. */
@Composable
fun VideoFeedCard(item: MediaItem, onClick: () -> Unit, onDownload: () -> Unit, onChannel: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().pressable(scaleDown = 0.985f, role = null, onClick = onClick).padding(bottom = 18.dp)) {
        Thumbnail(item.thumbnail, Modifier.padding(horizontal = 16.dp).fillMaxWidth().aspectRatio(16f / 9f), corner = 20) {
            CornerLabel(if (item.isLive) "LIVE" else Format.duration(item.durationSec), live = item.isLive)
        }
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.Top) {
            Avatar(item.uploaderAvatar, 38, Modifier.then(if (onChannel != null) Modifier.pressable(scaleDown = 0.9f, onClick = onChannel) else Modifier))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    item.subtitle(), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            DownloadIconButton(onDownload)
        }
    }
}

/** Compact list row: thumbnail on the left. Used for search results and related videos. */
@Composable
fun MediaRow(
    item: MediaItem,
    onClick: () -> Unit,
    onDownload: (() -> Unit)? = null,
    onMore: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().pressable(scaleDown = 0.98f, role = null, onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.kind == ItemKind.CHANNEL) {
            Box(Modifier.width(148.dp), contentAlignment = Alignment.Center) { Avatar(item.thumbnail, 76) }
        } else {
            Thumbnail(item.thumbnail, Modifier.width(148.dp).aspectRatio(16f / 9f), corner = 14) {
                when (item.kind) {
                    ItemKind.PLAYLIST -> Box(
                        Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.38f).height(200.dp)
                            .background(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f)))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(QtIcons.PlaylistPlay, null, tint = Color.White, modifier = Modifier.size(20.dp))
                            if (item.streamCount >= 0) Text("${item.streamCount}", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    else -> CornerLabel(if (item.isLive) "LIVE" else Format.duration(item.durationSec), live = item.isLive)
                }
            }
        }
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 4.dp)) {
            Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                item.subtitle(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (onDownload != null && item.kind == ItemKind.VIDEO && !item.isLive) DownloadIconButton(onDownload)
        else if (onMore != null) QtIconButton(QtIcons.MoreVert, "More", onMore, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Square card used in horizontal music rows. */
@Composable
fun MediaTile(item: MediaItem, onClick: () -> Unit, onDownload: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.width(160.dp).pressable(scaleDown = 0.96f, role = null, onClick = onClick)) {
        Thumbnail(item.thumbnail, Modifier.fillMaxWidth().aspectRatio(1f), corner = 18) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.45f)))))
            if (item.kind == ItemKind.PLAYLIST) Icon(
                QtIcons.PlaylistPlay, null, tint = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp).size(22.dp),
            ) else CornerLabel(Format.duration(item.durationSec))
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(34.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f))
                    .pressable(scaleDown = 0.85f, onClick = onDownload),
                contentAlignment = Alignment.Center,
            ) { Icon(QtIcons.Download, "Download", tint = Color.White, modifier = Modifier.size(18.dp)) }
        }
        Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        Text(item.subtitle(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
}
