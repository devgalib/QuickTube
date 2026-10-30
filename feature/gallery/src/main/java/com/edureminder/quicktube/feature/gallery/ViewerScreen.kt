package com.edureminder.quicktube.feature.gallery

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.edureminder.quicktube.core.common.Format
import com.edureminder.quicktube.core.designsystem.QtIconButton
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.model.GalleryMedia
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BarColor = Color(0xF0141414)

/**
 * Full-screen viewer like the system gallery's: swipe between items, pinch / double-tap to zoom, tap to hide the
 * bars (and the system bars) for an immersive view. Videos show their poster with a play button.
 */
@Composable
fun ViewerScreen(
    mediaId: Long,
    bucketId: Long,
    onBack: () -> Unit,
    onOpenVideo: (GalleryMedia) -> Unit,
) {
    val vm = koinViewModel<GalleryViewModel>(key = "viewer-$bucketId") { parametersOf(bucketId) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!ui.loaded) return@Box
        val items = ui.items
        if (items.isEmpty()) {
            LaunchedEffect(Unit) { onBack() }
            return@Box
        }
        val start = remember { items.indexOfFirst { it.id == mediaId }.coerceAtLeast(0) }
        Viewer(items, start, onBack, onPlay = onOpenVideo)
    }
}

@Composable
private fun Viewer(items: List<GalleryMedia>, start: Int, onBack: () -> Unit, onPlay: (GalleryMedia) -> Unit) {
    val context = LocalContext.current
    val pager = rememberPagerState(start) { items.size }
    var chrome by rememberSaveable { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<GalleryMedia?>(null) }
    val current = items.getOrNull(pager.currentPage.coerceAtMost(items.lastIndex)) ?: return
    val delete = rememberDeleteAction(onDeleted = {})
    ImmersiveBars(visible = chrome)

    HorizontalPager(
        pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, userScrollEnabled = !zoomed,
        key = { items[it].id }, pageSpacing = 12.dp,
    ) { page ->
        val media = items[page]
        Box(Modifier.fillMaxSize().clipToBounds()) {
            if (media.isVideo) VideoPage(media, onTap = { chrome = !chrome }, onPlay = { onPlay(media) })
            else PhotoPage(media, active = page == pager.currentPage, onTap = { chrome = !chrome }, onZoom = { if (page == pager.currentPage) zoomed = it })
        }
    }

    AnimatedVisibility(chrome, enter = fadeIn(tween(160)) + slideInVertically { -it / 3 }, exit = fadeOut(tween(160)) + slideOutVertically { -it / 3 }) {
        ViewerTopBar(current, onBack)
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(chrome, enter = fadeIn(tween(160)) + slideInVertically { it / 3 }, exit = fadeOut(tween(160)) + slideOutVertically { it / 3 }) {
            ViewerBottomBar(
                current,
                onShare = { GalleryActions.share(context, listOf(current)) },
                onEdit = { GalleryActions.edit(context, current) },
                onDelete = { delete(listOf(current)) },
                onDetails = { details = current },
            )
        }
    }
    details?.let { DetailsDialog(it) { details = null } }
}

@Composable
private fun PhotoPage(media: GalleryMedia, active: Boolean, onTap: () -> Unit, onZoom: (Boolean) -> Unit) {
    val context = LocalContext.current
    val aspect = if (media.width > 0 && media.height > 0) media.width.toFloat() / media.height else 0f
    val zoom = rememberZoomState(media.id, aspect)
    val scope = rememberCoroutineScope()
    LaunchedEffect(zoom.zoomed) { onZoom(zoom.zoomed) }
    // Leaving a page resets its zoom, as in the system gallery.
    LaunchedEffect(active) { if (!active) zoom.reset() }
    Box(Modifier.fillMaxSize().zoomable(zoom, scope, onTap)) {
        // The grid's thumbnail is already in memory: it shows instantly while the full picture decodes on top.
        GalleryThumbnail(media, Modifier.fillMaxSize(), crop = false)
        val request = remember(media.id, media.modifiedSec) {
            ImageRequest.Builder(context).data(media.uri).crossfade(120).build()
        }
        AsyncImage(request, media.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
}

@Composable
private fun VideoPage(media: GalleryMedia, onTap: () -> Unit, onPlay: () -> Unit) {
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onTap() } }, contentAlignment = Alignment.Center) {
        GalleryThumbnail(media, Modifier.fillMaxSize(), crop = false)
        GalleryThumbnail(media, Modifier.fillMaxSize(), sizePx = 720, crop = false)
        Box(Modifier.pointerInput(Unit) { detectTapGestures { onPlay() } }) { PlayBadge(size = 68.dp) }
        if (media.durationMs > 0) Text(
            Format.durationMs(media.durationMs), color = Color.White, fontSize = 13.sp,
            modifier = Modifier.align(Alignment.Center).padding(top = 108.dp),
        )
    }
}

@Composable
private fun ViewerTopBar(media: GalleryMedia, onBack: () -> Unit) {
    val date = remember(media.dateMs) { Date(media.dateMs) }
    Row(
        Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
            .statusBarsPadding().height(64.dp).padding(start = 6.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        QtIconButton(QtIcons.ArrowBack, "Back", onBack, tint = Color.White)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                remember(date) { SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(date) },
                color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleLarge,
            )
            Text(
                remember(date) { SimpleDateFormat("hh:mm a", Locale.getDefault()).format(date) },
                color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ViewerBottomBar(media: GalleryMedia, onShare: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit, onDetails: () -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().background(BarColor).navigationBarsPadding().height(62.dp).padding(horizontal = 4.dp)) {
        BarAction(QtIcons.Share, "Share", true, Color.White, onShare)
        BarAction(QtIcons.Edit, "Edit", true, Color.White, onEdit)
        BarAction(QtIcons.Delete, "Delete", true, Color.White, onDelete)
        Box(Modifier.weight(1f)) {
            Row { BarAction(QtIcons.MoreVert, "More", true, Color.White) { menu = true } }
            DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(18.dp)) {
                if (!media.isVideo) DropdownMenuItem({ Text("Set as wallpaper") }, { menu = false; GalleryActions.setAs(context, media) },
                    leadingIcon = { Icon(QtIcons.Wallpaper, null) })
                DropdownMenuItem({ Text("Open with") }, { menu = false; GalleryActions.openWith(context, media) },
                    leadingIcon = { Icon(QtIcons.OpenInNew, null) })
                DropdownMenuItem({ Text("Details") }, { menu = false; onDetails() }, leadingIcon = { Icon(QtIcons.Info, null) })
            }
        }
    }
}

@Composable
private fun DetailsDialog(media: GalleryMedia, onDismiss: () -> Unit) {
    val rows = remember(media) {
        listOfNotNull(
            "Title" to media.name.substringBeforeLast('.'),
            "Format" to media.mimeType,
            "Time" to SimpleDateFormat("MMMM d, yyyy  hh:mm a", Locale.getDefault()).format(Date(media.dateMs)),
            if (media.width > 0) "Resolution" to "${media.width} × ${media.height}" else null,
            if (media.isVideo && media.durationMs > 0) "Duration" to Format.durationMs(media.durationMs) else null,
            "File size" to Format.bytes(media.sizeBytes),
            "Album" to media.bucketName,
            "Path" to media.path,
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Details") },
        text = {
            Column {
                rows.forEach { (k, v) ->
                    Row(Modifier.padding(vertical = 5.dp)) {
                        Text("$k: ", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(v, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

/** Hides the status and navigation bars while the viewer's own bars are hidden; light icons on the black page. */
@Composable
internal fun ImmersiveBars(visible: Boolean) {
    val window = LocalActivity.current?.window ?: return
    val view = LocalView.current
    val controller = remember(window, view) { WindowCompat.getInsetsController(window, view) }
    DisposableEffect(controller) {
        val lightStatus = controller.isAppearanceLightStatusBars
        val lightNav = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.isAppearanceLightStatusBars = lightStatus
            controller.isAppearanceLightNavigationBars = lightNav
        }
    }
    LaunchedEffect(visible) {
        if (visible) controller.show(WindowInsetsCompat.Type.systemBars()) else controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}
