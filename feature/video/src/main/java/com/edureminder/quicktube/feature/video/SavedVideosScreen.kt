package com.edureminder.quicktube.feature.video

import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import com.edureminder.quicktube.core.designsystem.QtIconButton
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.edureminder.quicktube.core.data.SavedVideosRepository
import com.edureminder.quicktube.core.designsystem.QtTopBar
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.downloadsheet.DownloadSheetController
import com.edureminder.quicktube.core.model.MediaItem
import com.edureminder.quicktube.core.model.SavedVideo
import com.edureminder.quicktube.core.ui.EmptyBox
import com.edureminder.quicktube.core.ui.MediaRow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

class SavedVideosViewModel(private val repo: SavedVideosRepository) : ViewModel() {
    val saved = repo.saved.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    fun remove(url: String) = viewModelScope.launch { repo.remove(listOf(url)) }
}

private fun SavedVideo.asItem() = MediaItem(url = url, title = title, thumbnail = thumbnail, uploader = uploader, durationSec = durationSec)

/** Saved / Watch later: videos saved from the video page, newest first, to watch or download later. */
@Composable
fun SavedVideosScreen(onBack: () -> Unit, onOpen: (MediaItem) -> Unit) {
    val vm = koinViewModel<SavedVideosViewModel>()
    val saved by vm.saved.collectAsStateWithLifecycle()
    val sheet = koinInject<DownloadSheetController>()
    val context = LocalContext.current
    val list = saved
    Column(Modifier.fillMaxSize()) {
        QtTopBar("Watch later", onBack, subtitle = list?.takeIf { it.isNotEmpty() }?.let { "${it.size} saved video${if (it.size == 1) "" else "s"}" })
        when {
            list == null -> {}
            list.isEmpty() -> EmptyBox("Nothing saved yet", "Tap Watch later on a video to keep it here for later", icon = QtIcons.BookmarkBorder)
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.url }) { v ->
                    Row(Modifier.animateItem(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { MediaRow(v.asItem(), onClick = { onOpen(v.asItem()) }, onDownload = { sheet.open(v.asItem()) }) }
                        QtIconButton(QtIcons.Bookmark, "Remove from Watch later", {
                            vm.remove(v.url)
                            Toast.makeText(context, "Removed from Watch later", Toast.LENGTH_SHORT).show()
                        }, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 6.dp))
                    }
                }
                item { Spacer(Modifier.navigationBarsPadding().height(16.dp)) }
            }
        }
    }
}
