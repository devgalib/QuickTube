package com.edureminder.quicktube.feature.home

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.edureminder.quicktube.core.model.PromoGame

@Composable
internal actual fun rememberGameOpener(): (PromoGame) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { game ->
            val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${game.packageName}"))
                .setPackage("com.android.vending").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(market)
            } catch (_: ActivityNotFoundException) {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(game.storeUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
    }
}
