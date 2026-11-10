package com.edureminder.quicktube.feature.gallery

import com.edureminder.quicktube.core.media.PlayItem
import com.edureminder.quicktube.core.media.PlayerController
import com.edureminder.quicktube.core.model.GalleryMedia

/**
 * Songs and videos from the gallery as the player's queue: a folder played from any song, "Play all", "Shuffle", a
 * custom queue picked across folders, and "Add to queue" / "Play next" while something plays.
 */
object GalleryQueue {
    @Volatile private var handoff: Long? = null

    fun item(m: GalleryMedia) = PlayItem(
        id = "gallery:${m.id}", uri = m.uri.toString(), title = m.name.substringBeforeLast('.'), artist = m.artist, isVideo = !m.isAudio,
        historyKey = m.path.ifBlank { null },
    )

    /**
     * Starts [items] from [start] and returns the item to open the player on; the player then keeps this queue
     * instead of building the folder's (see [takeHandoff]).
     */
    fun play(controller: PlayerController, items: List<GalleryMedia>, start: Int = 0): GalleryMedia? {
        val first = items.getOrNull(start) ?: return null
        controller.play(items.map(::item), start, audioOnly = first.isAudio)
        controller.setVolume(1f)
        handoff = first.id
        return first
    }

    /** True once for the player opened by [play] on [mediaId]. */
    fun takeHandoff(mediaId: Long): Boolean = (handoff == mediaId).also { if (it) handoff = null }

    /** True while the player's queue is gallery media (so "Add to queue" adds to it rather than starting a new one). */
    fun isPlayingGallery(controller: PlayerController) = controller.state.value.queue.any { it.id.startsWith("gallery:") }

    fun add(controller: PlayerController, items: List<GalleryMedia>, next: Boolean = false) = controller.addToQueue(items.map(::item), next)
}
