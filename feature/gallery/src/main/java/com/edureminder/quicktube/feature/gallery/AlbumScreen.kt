package com.edureminder.quicktube.feature.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edureminder.quicktube.core.model.GalleryMedia
import com.edureminder.quicktube.core.navigation.GALLERY_AUDIOS
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** One folder or virtual album: back + name in the toolbar, then the month-grouped grid (or the audio list). */
@Composable
fun AlbumScreen(
    bucketId: Long,
    name: String,
    onBack: () -> Unit,
    onOpenViewer: (GalleryMedia) -> Unit,
    /** Opens the player for a video or audio file. */
    onOpenVideo: (GalleryMedia) -> Unit,
) {
    val vm = koinViewModel<GalleryViewModel>(key = "gallery-$bucketId") { parametersOf(bucketId) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    var selection by remember { mutableStateOf<Set<Long>?>(null) }
    val state = rememberLazyGridState()
    BackHandler(selection != null) { selection = null }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            GalleryToolbar(name, collapsed = true, selectionCount = selection?.size, onCloseSelection = { selection = null }, onBack = onBack)
            val bottom = PaddingValues(bottom = if (selection != null) 96.dp else 0.dp)
            if (bucketId == GALLERY_AUDIOS) AudioList(ui.items, selection, { selection = it }, onOpenVideo, bottom)
            else MediaGrid(
                ui, state, selection, { selection = it },
                onOpen = { if (it.isPhoto) onOpenViewer(it) else onOpenVideo(it) },
                contentPadding = bottom,
            )
        }
        selection?.let { sel ->
            SelectionBar(
                selected = remember(sel, ui.items) { ui.items.filter { it.id in sel } },
                allSelected = sel.size == ui.items.size,
                onSelectAll = { selection = if (sel.size == ui.items.size) emptySet() else ui.items.mapTo(HashSet()) { it.id } },
                onDone = { selection = null },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
