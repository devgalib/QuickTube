package com.edureminder.quicktube.feature.gallery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.edureminder.quicktube.core.data.GalleryRepository
import com.edureminder.quicktube.core.model.GalleryAlbum
import com.edureminder.quicktube.core.model.GalleryMedia
import com.edureminder.quicktube.core.navigation.GALLERY_ALL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

data class GalleryUi(
    val loaded: Boolean = false,
    val complete: Boolean = false,
    val items: List<GalleryMedia> = emptyList(),
    val entries: List<GridEntry> = emptyList(),
    val albums: List<GalleryAlbum> = emptyList(),
    /** The Audios tab: every folder with audio files. */
    val audioFolders: List<GalleryAlbum> = emptyList(),
    /** Every audio file (for "Play all", "Shuffle" and the custom-queue picker). */
    val audios: List<GalleryMedia> = emptyList(),
)

/** One per gallery screen: the Photos tab ([GALLERY_ALL]), a virtual album or one folder ([bucketId]). Sections are built off the main thread. */
@OptIn(ExperimentalCoroutinesApi::class)
class GalleryViewModel(
    private val bucketId: Long,
    private val repo: GalleryRepository,
    /** An audio folder ([bucketId] is the folder): its songs only. */
    private val audio: Boolean = false,
) : ViewModel() {

    val ui: StateFlow<GalleryUi> = repo.library.filterNotNull()
        .mapLatest { lib ->
            val items = GallerySections.filter(lib.items, bucketId, audio)
            GalleryUi(
                loaded = true,
                complete = lib.complete,
                items = items,
                entries = GallerySections.build(items),
                albums = if (bucketId == GALLERY_ALL) GallerySections.virtualAlbums(lib.items) + GallerySections.albums(items) else emptyList(),
                audioFolders = if (bucketId == GALLERY_ALL && !audio) GallerySections.audioFolders(lib.items) else emptyList(),
                audios = if (bucketId == GALLERY_ALL && !audio) lib.items.filter { it.isAudio } else emptyList(),
            )
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GalleryUi())

    fun load(force: Boolean = false) = repo.load(force)
}
