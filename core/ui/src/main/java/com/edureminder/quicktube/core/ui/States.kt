package com.edureminder.quicktube.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.edureminder.quicktube.core.designsystem.LocalQtExtras
import com.edureminder.quicktube.core.designsystem.PillButton
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.designsystem.shimmer

@Composable
fun LoadingBox(modifier: Modifier = Modifier.fillMaxSize()) {
    Box(modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
    }
}

@Composable
fun ErrorBox(message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier.fillMaxSize()) {
    MessageBox(QtIcons.CloudOff, "Something went wrong", message, modifier, onRetry?.let { "Try again" to it })
}

@Composable
fun EmptyBox(title: String, message: String = "", modifier: Modifier = Modifier.fillMaxSize(), icon: ImageVector = QtIcons.Inbox) {
    MessageBox(icon, title, message, modifier, null)
}

@Composable
fun MessageBox(icon: ImageVector, title: String, message: String, modifier: Modifier, action: Pair<String, () -> Unit>?) {
    val ex = LocalQtExtras.current
    Column(modifier.padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(88.dp).clip(CircleShape).background(ex.brandStart.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(40.dp), tint = ex.brandStart)
        }
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 18.dp), textAlign = TextAlign.Center)
        if (message.isNotBlank()) Text(
            message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp),
        )
        if (action != null) PillButton(action.first, action.second, Modifier.padding(top = 20.dp), icon = QtIcons.Refresh)
    }
}

@Composable
fun FooterLoading() {
    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 3.dp)
    }
}

// ---------- Skeletons (shown while content loads) ----------

@Composable
private fun Bone(modifier: Modifier, corner: Int = 8) = Box(modifier.clip(RoundedCornerShape(corner.dp)).shimmer())

@Composable
fun FeedSkeleton(count: Int = 3) {
    Column(Modifier.fillMaxSize()) {
        repeat(count) {
            Column(Modifier.padding(bottom = 18.dp)) {
                Bone(Modifier.padding(horizontal = 16.dp).fillMaxWidth().aspectRatio(16f / 9f), 20)
                Row(Modifier.padding(16.dp, 12.dp)) {
                    Bone(Modifier.size(38.dp), 19)
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Bone(Modifier.fillMaxWidth(0.9f).height(14.dp))
                        Bone(Modifier.padding(top = 8.dp).fillMaxWidth(0.55f).height(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun RowsSkeleton(count: Int = 7) {
    Column(Modifier.fillMaxSize().padding(top = 6.dp)) {
        repeat(count) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Bone(Modifier.width(148.dp).aspectRatio(16f / 9f), 14)
                Column(Modifier.padding(start = 14.dp).weight(1f)) {
                    Bone(Modifier.fillMaxWidth().height(13.dp))
                    Bone(Modifier.padding(top = 7.dp).fillMaxWidth(0.7f).height(13.dp))
                    Bone(Modifier.padding(top = 10.dp).fillMaxWidth(0.45f).height(11.dp))
                }
            }
        }
    }
}

@Composable
fun TilesSkeleton(sections: Int = 3) {
    Column(Modifier.fillMaxSize()) {
        repeat(sections) {
            Bone(Modifier.padding(start = 20.dp, top = 22.dp, bottom = 12.dp).width(160.dp).height(18.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(3) {
                    Column {
                        Bone(Modifier.size(160.dp), 18)
                        Bone(Modifier.padding(top = 10.dp).width(130.dp).height(13.dp))
                    }
                }
            }
        }
    }
}

/** Calls [onLoadMore] when the list is scrolled close to the end. */
@Composable
fun LazyListState.OnNearEnd(buffer: Int = 4, onLoadMore: () -> Unit) {
    val load by rememberUpdatedState(onLoadMore)
    val near by remember {
        derivedStateOf {
            val info = layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            info.totalItemsCount > 0 && last >= info.totalItemsCount - 1 - buffer
        }
    }
    LaunchedEffect(near) { if (near) load() }
}
