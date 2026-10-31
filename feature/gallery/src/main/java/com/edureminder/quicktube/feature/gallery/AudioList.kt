package com.edureminder.quicktube.feature.gallery

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edureminder.quicktube.core.common.Format
import com.edureminder.quicktube.core.model.GalleryMedia

/**
 * The Audios album: every audio file on the phone, newest first. Rows show album art (or a music note), the title, the
 * artist or folder, and the length. Tap plays; long-press starts selecting, like the photo grid.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AudioList(
    items: List<GalleryMedia>,
    selection: Set<Long>?,
    onSelection: (Set<Long>?) -> Unit,
    onOpen: (GalleryMedia) -> Unit,
    contentPadding: PaddingValues,
) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        items(items, key = { it.id }, contentType = { "audio" }) { media ->
            val selected = selection?.contains(media.id)
            Row(
                Modifier.fillMaxWidth().height(68.dp)
                    .combinedClickable(
                        onClick = { if (selection != null) onSelection(GallerySelection.toggle(selection, media.id)) else onOpen(media) },
                        onLongClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSelection(GallerySelection.toggle(selection.orEmpty(), media.id))
                        },
                    )
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AudioArt(media, Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)), sizePx = 144, iconSize = 22.dp)
                Column(Modifier.weight(1f).padding(start = 14.dp, end = 10.dp)) {
                    Text(
                        media.name.substringBeforeLast('.'), style = MaterialTheme.typography.bodyLarge, fontSize = 15.sp,
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOf(media.artist.ifBlank { media.bucketName }, Format.bytes(media.sizeBytes)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (selected != null) Box(Modifier.padding(start = 4.dp)) { SelectBox(selected, outline = cs.onSurfaceVariant) }
                else if (media.durationMs > 0) Text(Format.durationMs(media.durationMs), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            }
        }
    }
}
