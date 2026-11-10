package com.edureminder.quicktube.core.common

/** Simple UI state wrapper used by screens that load one thing. */
sealed interface LoadState<out T> {
    data object Loading : LoadState<Nothing>
    data class Success<T>(val data: T) : LoadState<T>
    data class Error(val message: String) : LoadState<Nothing>
}

suspend fun <T> loadCatching(block: suspend () -> T): LoadState<T> = try {
    LoadState.Success(block())
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Throwable) {
    LoadState.Error(e.message ?: e.javaClass.simpleName)
}
