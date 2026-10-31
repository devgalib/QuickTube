package com.edureminder.quicktube.feature.gallery.player

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.designsystem.pressable
import com.edureminder.quicktube.core.media.PlaybackState
import com.edureminder.quicktube.core.media.PlayerController
import com.edureminder.quicktube.core.model.GalleryMedia
import com.edureminder.quicktube.feature.gallery.AudioArt
import com.edureminder.quicktube.feature.gallery.GalleryThumbnail

private val Glass = Color.White.copy(alpha = 0.16f)

/**
 * "Play as audio": the video track is switched off and the system player's music layout takes over — a blurred
 * frame behind, the frame as artwork, quick chips, a big title with a heart, seek bar and transport controls.
 * Playback carries on in the background with the notification.
 */
@Composable
fun AudioModeScreen(
    media: GalleryMedia,
    state: PlaybackState,
    ui: GalleryPlayerUi,
    controller: PlayerController,
    onBackToVideo: () -> Unit,
    onClose: () -> Unit,
    onToMp3: () -> Unit,
    onFavorite: () -> Unit,
    onMode: () -> Unit,
    onMore: () -> Unit,
) {
    val position = rememberLivePosition(controller, true)
    Box(Modifier.fillMaxSize().background(Color(0xFF2A2A2E))) {
        // Blur needs Android 12; older versions get the dimmed frame alone.
        GalleryThumbnail(
            media, Modifier.fillMaxSize().then(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(60.dp) else Modifier),
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)))
        Column(Modifier.fillMaxSize().statusPadding().navigationBarsPadding()) {
            if (media.isAudio) {
                // An audio file has no video to go back to: a chevron closes the player instead.
                Box(
                    Modifier.padding(start = 10.dp, top = 4.dp).size(44.dp).clip(CircleShape).pressable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Icon(QtIcons.PDown, "Close", tint = Color.White, modifier = Modifier.size(30.dp)) }
                Spacer(Modifier.height(8.dp))
            } else Spacer(Modifier.height(39.dp))
            Box(
                Modifier.padding(horizontal = 22.dp).fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp)).background(Glass),
            ) {
                if (media.isAudio) AudioArt(media, Modifier.fillMaxSize(), sizePx = 720, iconSize = 96.dp)
                else GalleryThumbnail(media, Modifier.fillMaxSize(), sizePx = 720)
            }
            Spacer(Modifier.height(25.dp))
            if (media.isAudio) {
                if (media.artist.isNotBlank()) Text(media.artist, color = Color.White.copy(alpha = 0.75f), fontSize = 15.sp, modifier = Modifier.padding(horizontal = 22.dp))
            } else LazyRow(contentPadding = PaddingValues(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(17.dp)) {
                item { Chip(QtIcons.Videocam, "Back", onBackToVideo) }
                item { Chip(QtIcons.Sync, "Video to MP3", onToMp3) }
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.padding(horizontal = 22.dp), verticalAlignment = Alignment.Bottom) {
                Text(
                    media.name.substringBeforeLast('.'), Modifier.weight(1f), color = Color.White, fontSize = 25.sp,
                    fontWeight = FontWeight.Bold, lineHeight = 30.sp, maxLines = 2,
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Box(
                    Modifier.padding(start = 12.dp).size(36.dp).clip(CircleShape).background(Glass).pressable(onClick = onFavorite),
                    contentAlignment = Alignment.Center,
                ) { Icon(if (ui.favorite) QtIcons.PFavorite else QtIcons.PFavoriteBorder, "Favorite", tint = Color.White, modifier = Modifier.size(22.dp)) }
            }
            Spacer(Modifier.height(40.dp))
            PillSeekBar(position, state.durationMs, controller::seekTo, Modifier.fillMaxWidth().padding(horizontal = 30.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp)) {
                LiveClock(position, Color.White.copy(alpha = 0.7f))
                Spacer(Modifier.weight(1f))
                Text(clock(state.durationMs), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Transport(modeIcon(ui.mode), ui.mode.label, 24.dp, onMode)
                Transport(QtIcons.PSkipPrevious, "Previous", 40.dp, controller::previous)
                Transport(if (state.isPlaying) QtIcons.PPause else QtIcons.PPlay, "Play", 56.dp, controller::togglePlay)
                Transport(QtIcons.PSkipNext, "Next", 40.dp, controller::next)
                Transport(QtIcons.PMore, "More", 24.dp, onMore)
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

private fun Modifier.statusPadding() = this.statusBarsPadding()

@Composable
private fun androidx.compose.foundation.layout.RowScope.Transport(icon: ImageVector, description: String, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
    Box(Modifier.weight(1f).height(64.dp).clip(CircleShape).pressable(scaleDown = 0.88f, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = Color.White, modifier = Modifier.size(size))
    }
}

@Composable
private fun Chip(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.height(30.dp).clip(CircleShape).background(Glass).pressable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

/** Audio mode's ⋮: file name, four round shortcuts, then the list. */
@Composable
fun AudioMoreSheet(
    media: GalleryMedia,
    onEqualizer: () -> Unit,
    onTimer: () -> Unit,
    onSpeed: () -> Unit,
    onShare: () -> Unit,
    onInfo: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    PlayerSheet(onDismiss) {
        Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.width(36.dp).height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.3f)))
        }
        Text(media.name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, modifier = Modifier.padding(start = 24.dp, top = 22.dp, end = 24.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 22.dp)) {
            Round(QtIcons.PEqualizer, "Equalizer", onEqualizer)
            Round(QtIcons.PTimer, "Timer", onTimer)
            Round(QtIcons.PSpeed, "Speed", onSpeed)
            Round(QtIcons.PShare, "Share", onShare)
        }
        ListRow(QtIcons.PInfo, "Info", onInfo)
        ListRow(QtIcons.PDelete, "Delete", onDelete)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Round(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(48.dp).clip(CircleShape).background(Color.Black).pressable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
    }
}

@Composable
private fun ListRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(18.dp))
        Text(label, color = Color.White, fontSize = 16.sp)
    }
}
