package com.edureminder.quicktube.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import com.edureminder.quicktube.core.model.PromoGame

@Composable
internal actual fun rememberGameOpener(): (PromoGame) -> Unit {
    val uri = LocalUriHandler.current
    return { game -> runCatching { uri.openUri(game.storeUrl) } }
}
