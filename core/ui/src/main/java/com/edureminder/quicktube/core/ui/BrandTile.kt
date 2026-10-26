package com.edureminder.quicktube.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.edureminder.quicktube.core.designsystem.icon.BrandIcons

class BrandSpec(val icon: ImageVector?, val background: Brush, val tint: Color = Color.White)

/** Brand mark + colours for a site, looked up by name or URL. */
fun brandFor(nameOrUrl: String): BrandSpec? {
    val k = nameOrUrl.lowercase()
    return when {
        "youtube" in k || "youtu.be" in k -> BrandSpec(BrandIcons.YouTube, SolidColor(Color(0xFFFF0033)))
        "instagram" in k -> BrandSpec(
            BrandIcons.Instagram,
            Brush.linearGradient(listOf(Color(0xFFFEDA75), Color(0xFFFA7E1E), Color(0xFFD62976), Color(0xFF962FBF), Color(0xFF4F5BD5))),
        )
        "facebook" in k || "fb.watch" in k -> BrandSpec(BrandIcons.Facebook, SolidColor(Color(0xFF0866FF)))
        "tiktok" in k -> BrandSpec(BrandIcons.TikTok, Brush.linearGradient(listOf(Color(0xFF111111), Color(0xFF2B2B2B))))
        "twitter" in k || k == "x" || "x.com" in k || k.startsWith("twitter/x") -> BrandSpec(BrandIcons.X, SolidColor(Color(0xFF0F0F0F)))
        "whatsapp" in k || "status" in k -> BrandSpec(BrandIcons.WhatsApp, SolidColor(Color(0xFF25D366)))
        "dailymotion" in k -> BrandSpec(BrandIcons.Dailymotion, SolidColor(Color(0xFF0A0A0A)))
        "vimeo" in k -> BrandSpec(BrandIcons.Vimeo, SolidColor(Color(0xFF17D5FF)))
        "soundcloud" in k -> BrandSpec(BrandIcons.SoundCloud, Brush.linearGradient(listOf(Color(0xFFFF8800), Color(0xFFFF3300))))
        else -> null
    }
}

/** Rounded tile with the site's real logo (or its initial on an accent tile for unknown sites). */
@Composable
fun BrandTile(nameOrUrl: String, modifier: Modifier = Modifier, size: Dp = 52.dp, label: String = nameOrUrl) {
    val spec = brandFor(nameOrUrl)
    val shape = RoundedCornerShape(size * 0.32f)
    Box(
        modifier.size(size).clip(shape)
            .background(spec?.background ?: SolidColor(MaterialTheme.colorScheme.primaryContainer)),
        contentAlignment = Alignment.Center,
    ) {
        if (spec?.icon != null) Icon(spec.icon, null, tint = spec.tint, modifier = Modifier.size(size * 0.5f))
        else Text(
            label.trim().take(1).uppercase(), color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold,
        )
    }
}
