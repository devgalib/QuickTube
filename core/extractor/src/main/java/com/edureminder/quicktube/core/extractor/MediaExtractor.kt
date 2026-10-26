package com.edureminder.quicktube.core.extractor

import com.edureminder.quicktube.core.model.ChannelDetails
import com.edureminder.quicktube.core.model.Comment
import com.edureminder.quicktube.core.model.ItemKind
import com.edureminder.quicktube.core.model.MediaItem
import com.edureminder.quicktube.core.model.MediaSection
import com.edureminder.quicktube.core.model.Page
import com.edureminder.quicktube.core.model.PlaylistDetails
import com.edureminder.quicktube.core.model.SearchFilter
import com.edureminder.quicktube.core.model.VideoDetails

/** Platform-agnostic contract for online media lookups (search, feeds, stream info). */
interface MediaExtractor {
    fun canHandle(url: String): Boolean
    fun linkKind(url: String): ItemKind?
    suspend fun suggestions(query: String): List<String>
    suspend fun search(query: String, filter: SearchFilter, page: Any? = null): Page<MediaItem>
    suspend fun trending(page: Any? = null): Page<MediaItem>
    suspend fun musicHome(): List<MediaSection>
    suspend fun details(url: String): VideoDetails
    suspend fun comments(url: String, page: Any? = null): Page<Comment>
    suspend fun channel(url: String, page: Any? = null): ChannelDetails
    suspend fun playlist(url: String, page: Any? = null): PlaylistDetails
}
