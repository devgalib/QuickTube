package com.edureminder.quicktube.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.edureminder.quicktube.core.designsystem.pressable

/**
 * How wide the window is, following Material's window size classes: phones in portrait are [COMPACT], large phones in
 * landscape, foldables and small tablets [MEDIUM], tablets in landscape and desktop windows [EXPANDED].
 */
enum class WidthClass {
    COMPACT, MEDIUM, EXPANDED;

    companion object {
        fun of(width: Dp) = when {
            width < 600.dp -> COMPACT
            width < 840.dp -> MEDIUM
            else -> EXPANDED
        }
    }
}

/** The app window's width class, provided by the app shell (phone, tablet or desktop window). */
val LocalWidthClass = compositionLocalOf { WidthClass.COMPACT }

/** True when there's room for a side rail and side-by-side panes. */
val WidthClass.isWide get() = this != WidthClass.COMPACT

/**
 * Content kept to a comfortable reading width on big screens (settings, forms, lists of rows), centred; on phones it
 * simply fills the width.
 */
@Composable
fun MaxWidth(max: Dp = 760.dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = max).fillMaxWidth()) { content() }
    }
}

/** Columns for a grid of video cards: 1 on phones, more as the window grows (cards stay about 300–420dp wide). */
@Composable
fun BoxWithConstraintsColumns(minCell: Dp = 300.dp, content: @Composable (columns: Int) -> Unit) {
    BoxWithConstraints {
        val columns = (maxWidth / minCell).toInt().coerceIn(1, 6)
        content(columns)
    }
}

/** One destination of the app's main navigation. */
class QtNavItem(val label: String, val icon: ImageVector, val selectedIcon: ImageVector = icon)

/**
 * The side navigation used on tablets and desktop windows in place of the phone's floating bottom bar: the app's
 * tabs as rounded pills down the left edge, with an optional [header] (logo) and [footer].
 */
@Composable
fun QtNavRail(
    items: List<QtNavItem>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    badges: Map<Int, Int> = emptyMap(),
    expanded: Boolean = false,
    header: @Composable ColumnScope.() -> Unit = {},
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.fillMaxHeight().width(if (expanded) 220.dp else 88.dp).background(cs.surfaceContainerLow).systemBarsPadding()
            .padding(vertical = 12.dp, horizontal = if (expanded) 12.dp else 8.dp),
        horizontalAlignment = if (expanded) Alignment.Start else Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        header()
        Spacer(Modifier.height(8.dp))
        items.forEachIndexed { i, item ->
            val on = i == selected
            val bg by animateColorAsState(if (on) cs.primary.copy(alpha = 0.16f) else Color.Transparent, label = "rail")
            val tint = if (on) cs.primary else cs.onSurfaceVariant
            if (expanded) {
                androidx.compose.foundation.layout.Row(
                    Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(24.dp)).background(bg)
                        .pressable(scaleDown = 0.97f, onClick = { onSelect(i) }).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BadgedIcon(if (on) item.selectedIcon else item.icon, tint, badges[i] ?: 0)
                    Text(
                        item.label, style = MaterialTheme.typography.labelLarge, color = if (on) cs.onSurface else cs.onSurfaceVariant,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.SemiBold, modifier = Modifier.padding(start = 14.dp),
                    )
                }
            } else {
                Column(
                    Modifier.width(72.dp).clip(RoundedCornerShape(18.dp)).pressable(scaleDown = 0.95f, onClick = { onSelect(i) }).padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.width(56.dp).height(32.dp).clip(RoundedCornerShape(16.dp)).background(bg), contentAlignment = Alignment.Center) {
                        BadgedIcon(if (on) item.selectedIcon else item.icon, tint, badges[i] ?: 0)
                    }
                    Text(
                        item.label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 1,
                        color = if (on) cs.onSurface else cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        footer()
    }
}

@Composable
private fun BadgedIcon(icon: ImageVector, tint: Color, badge: Int) {
    Box {
        Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        if (badge > 0) Box(
            Modifier.align(Alignment.TopEnd).padding(start = 14.dp).size(16.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error),
            contentAlignment = Alignment.Center,
        ) { Text(if (badge > 9) "9+" else "$badge", color = Color.White, style = MaterialTheme.typography.labelSmall) }
    }
}

/** Side padding that centres content of at most [max] wide in a container [this] wide (0 on phones). */
fun Dp.centeringPadding(max: Dp): Dp = ((this - max) / 2).coerceAtLeast(0.dp)

/**
 * Keeps a screen's content to [max] wide, centred, on tablets and desktop windows (settings, lists of rows, forms);
 * on phones it simply fills the width. Put it after the size modifiers of the screen's root column or list.
 */
fun Modifier.readableWidth(max: Dp = 760.dp): Modifier =
    this.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = max).fillMaxWidth()
