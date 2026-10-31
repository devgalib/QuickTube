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
)

/** One per gallery screen: the Photos tab ([GALLERY_ALL]), a virtual album or one folder ([bucketId]). Sections are built off the main thread. */
@OptIn(ExperimentalCoroutinesApi::class)
class GalleryViewModel(
    private val bucketId: Long,
    private val repo: GalleryRepository,
) : ViewModel() {

    val ui: StateFlow<GalleryUi> = repo.library.filterNotNull()
        .mapLatest { lib ->
            val items = GallerySections.filter(lib.items, bucketId)
            GalleryUi(
                loaded = true,
                complete = lib.complete,
                items = items,
                entries = GallerySections.build(items),
                albums = if (bucketId == GALLERY_ALL) GallerySections.virtualAlbums(lib.items) + GallerySections.albums(items) else emptyList(),
            )
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GalleryUi())

    fun load(force: Boolean = false) = repo.load(force)
}
