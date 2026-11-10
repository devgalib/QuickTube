package com.edureminder.quicktube.desktop.screens

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edureminder.quicktube.core.common.Format
import com.edureminder.quicktube.core.data.LibraryRepository
import com.edureminder.quicktube.core.data.MediaRepository
import com.edureminder.quicktube.core.designsystem.PillButton
import com.edureminder.quicktube.core.designsystem.QtTopBar
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.designsystem.pressable
import com.edureminder.quicktube.core.downloadsheet.BatchDownloader
import com.edureminder.quicktube.core.downloadsheet.BatchQuality
import com.edureminder.quicktube.core.downloadsheet.DownloadSheetController
import com.edureminder.quicktube.core.model.MediaItem
import com.edureminder.quicktube.core.model.Page
import com.edureminder.quicktube.core.ui.Avatar
import com.edureminder.quicktube.core.ui.CornerLabel
import com.edureminder.quicktube.core.ui.DownloadIconButton
import com.edureminder.quicktube.core.ui.ErrorBox
import com.edureminder.quicktube.core.ui.FooterLoading
import com.edureminder.quicktube.core.ui.LoadingBox
import com.edureminder.quicktube.core.ui.QtBackHandler
import com.edureminder.quicktube.core.ui.Thumbnail
import com.edureminder.quicktube.core.ui.WatchedBar
import com.edureminder.quicktube.core.ui.rememberToaster
import com.edureminder.quicktube.core.ui.show
import com.edureminder.quicktube.core.ui.subtitle
import com.edureminder.quicktube.desktop.LocalWindowControls
import com.edureminder.quicktube.desktop.player.DesktopPlayer
import com.edureminder.quicktube.desktop.player.PlayerView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** The player on its own (downloaded files, or what the mini player was playing), filling the window. */
@Composable
fun DesktopPlayerScreen(onBack: () -> Unit) {
    val player = koinInject<DesktopPlayer>()
    val library = koinInject<LibraryRepository>()
    val s by player.state.collectAsState()
    val window = LocalWindowControls.current
    var fullscreen by remember { mutableStateOf(window.isFullscreen()) }
    QtBackHandler(fullscreen) { fullscreen = false; window.setFullscreen(false) }
    // Downloaded files remember where they were left (the Library resumes there).
    DisposableEffect(Unit) {
        onDispose {
            val now = player.state.value
            if (window.isFullscreen()) window.setFullscreen(false)
            val key = now.key ?: return@onDispose
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                library.downloads.first().firstOrNull { it.filePath == key }?.let { library.savePosition(it.id, now.positionMs) }
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (!fullscreen) QtTopBar(s.title.ifBlank { "Player" }, onBack, subtitle = s.subtitle.takeIf { it.isNotBlank() })
        PlayerView(
            player, Modifier.fillMaxWidth().weight(1f), fullscreen = fullscreen,
            onToggleFullscreen = { fullscreen = !fullscreen; window.setFullscreen(fullscreen) },
        )
    }
}
