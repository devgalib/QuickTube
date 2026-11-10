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
}
