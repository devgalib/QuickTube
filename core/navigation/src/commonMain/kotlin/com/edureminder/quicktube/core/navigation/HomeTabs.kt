package com.edureminder.quicktube.core.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Asks the Home screen to show one of its tabs (e.g. the built-in YouTube from the Sites list). */
object HomeTabs {
    const val YOUTUBE = 1

    private val _request = MutableStateFlow<Int?>(null)
    /** The tab to show next; Home clears it once shown. */
    val request: StateFlow<Int?> = _request

    fun show(tab: Int) { _request.value = tab }
    fun consumed() { _request.value = null }

    private val _search = MutableStateFlow<Pair<String, com.edureminder.quicktube.core.model.SearchFilter?>?>(null)
    /** A search to run in the YouTube tab, with an optional filter (it shows that tab and clears this once started). */
    val search: StateFlow<Pair<String, com.edureminder.quicktube.core.model.SearchFilter?>?> = _search

    fun searchYouTube(query: String, filter: com.edureminder.quicktube.core.model.SearchFilter? = null) {
        _search.value = query to filter
        show(YOUTUBE)
    }
    fun searchConsumed() { _search.value = null }
}
