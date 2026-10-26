package com.edureminder.quicktube.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edureminder.quicktube.core.designsystem.LocalQtExtras
import kotlin.math.roundToInt

data class NavTab(val route: Any, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

/**
 * Floating navigation bar with equal slots and a gradient pill that slides between them.
 * The pill is positioned in the layout phase (offset lambda) and reacts to the tap immediately,
 * so it never waits for the next screen to compose.
 */
@Composable
fun FloatingNavBar(
    tabs: List<NavTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    badges: Map<Int, Int> = emptyMap(),
) {
    val cs = MaterialTheme.colorScheme
    val ex = LocalQtExtras.current
    val haptic = LocalHapticFeedback.current
    // Local selection so the pill moves on tap, before navigation finishes.
    var current by remember { mutableIntStateOf(selected.coerceAtLeast(0)) }
    LaunchedEffect(selected) { if (selected >= 0) current = selected }
    val position by animateFloatAsState(current.toFloat(), spring(Spring.DampingRatioLowBouncy, Spring.StiffnessMediumLow), label = "pill")
    val shape = RoundedCornerShape(28.dp)

    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        BoxWithConstraints(
            Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .height(68.dp)
                .shadow(6.dp, shape, ambientColor = Color.Black.copy(alpha = 0.4f), spotColor = Color.Black.copy(alpha = 0.4f))
                .clip(shape)
                .background(cs.surfaceContainerHigh)
                .border(1.dp, cs.outlineVariant.copy(alpha = if (ex.isDark) 0.7f else 1f), shape)
                .padding(horizontal = 8.dp),
        ) {
            val slot = maxWidth / tabs.size
            val density = LocalDensity.current
            val slotPx = with(density) { slot.toPx() }
            val pillW = 58.dp
            val pillPx = with(density) { pillW.toPx() }
            // Sliding gradient pill behind the active icon.
            Box(
                Modifier
                    .offset { IntOffset((position * slotPx + (slotPx - pillPx) / 2).roundToInt(), with(density) { 9.dp.roundToPx() }) }
                    .size(pillW, 32.dp)
                    .clip(CircleShape)
                    .background(ex.brand),
            )
            Row(Modifier.fillMaxSize()) {
                tabs.forEachIndexed { i, tab ->
                    val isSel = i == current
                    val iconTint by animateColorAsState(if (isSel) cs.onPrimary else cs.onSurfaceVariant, tween(180), label = "i")
                    val labelTint by animateColorAsState(if (isSel) cs.onSurface else cs.onSurfaceVariant, tween(180), label = "l")
                    val scale by animateFloatAsState(if (isSel) 1f else 0.94f, spring(stiffness = Spring.StiffnessMedium), label = "s")
                    Column(
                        Modifier
                            .width(slot)
                            .fillMaxHeight()
                            .selectable(
                                selected = isSel,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Tab,
                                onClick = {
                                    if (current != i) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    current = i
                                    onSelect(i)
                                },
                            )
                            .graphicsLayer { scaleX = scale; scaleY = scale },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.padding(top = 13.dp).size(24.dp), contentAlignment = Alignment.Center) {
                            Icon(if (isSel) tab.selectedIcon else tab.icon, tab.label, tint = iconTint, modifier = Modifier.size(23.dp))
                            val badge = badges[i] ?: 0
                            if (badge > 0) Box(
                                Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-5).dp).size(17.dp).clip(CircleShape)
                                    .background(cs.error).border(1.5.dp, cs.surfaceContainerHigh, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) { Text(if (badge > 9) "9+" else "$badge", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
                        }
                        Text(
                            tab.label, color = labelTint, fontSize = 11.sp, maxLines = 1,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                            style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 7.dp),
                        )
                    }
                }
            }
        }
    }
}
