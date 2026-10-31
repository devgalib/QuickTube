package com.edureminder.quicktube.navigation

import androidx.compose.animation.animateColorAsState
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.Animatable
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
 * Floating navigation bar: a rounded container with equal slots and a gradient indicator that holds the whole selected
 * tab (icon and label), like the gallery's Photos | Albums switch. The indicator's two edges spring separately, so it
 * stretches toward the new tab and settles, and it moves on tap without waiting for the next screen.
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
    // Local selection so the indicator moves on tap, before navigation finishes.
    var current by remember { mutableIntStateOf(selected.coerceAtLeast(0)) }
    LaunchedEffect(selected) { if (selected >= 0) current = selected }
    val left = remember { Animatable(current.toFloat()) }
    val right = remember { Animatable(current + 1f) }
    LaunchedEffect(current) {
        // The leading edge is quick and the trailing one lags a little: a short stretch, then it settles.
        val forward = current > left.value
        val lead = spring<Float>(dampingRatio = 0.78f, stiffness = 900f)
        val trail = spring<Float>(dampingRatio = 0.72f, stiffness = 380f)
        launch { left.animateTo(current.toFloat(), if (forward) trail else lead) }
        launch { right.animateTo(current + 1f, if (forward) lead else trail) }
    }
    val shape = RoundedCornerShape(34.dp)

    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        BoxWithConstraints(
            Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .height(68.dp)
                .shadow(14.dp, shape, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.45f))
                .clip(shape)
                .background(cs.surfaceContainerHigh)
                .border(1.dp, cs.outlineVariant.copy(alpha = if (ex.isDark) 0.55f else 0.9f), shape)
                .padding(6.dp),
        ) {
            val slot = maxWidth / tabs.size
            val density = LocalDensity.current
            val slotPx = with(density) { slot.toPx() }
            // The indicator: placed and sized in the layout phase from the two animated edges (no recomposition).
            Box(
                Modifier
                    .fillMaxHeight()
                    .layout { m, c ->
                        val l = left.value * slotPx
                        val w = ((right.value - left.value) * slotPx).roundToInt().coerceAtLeast(1)
                        val p = m.measure(c.copy(minWidth = w, maxWidth = w))
                        layout(c.maxWidth, p.height) { p.place(l.roundToInt(), 0) }
                    }
                    .clip(RoundedCornerShape(28.dp))
                    .background(ex.brand),
            )
            Row(Modifier.fillMaxSize()) {
                tabs.forEachIndexed { i, tab ->
                    val isSel = i == current
                    val tint by animateColorAsState(if (isSel) cs.onPrimary else cs.onSurfaceVariant, tween(200), label = "t")
                    val interaction = remember { MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()
                    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "s")
                    Column(
                        Modifier
                            .width(slot)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(28.dp))
                            .selectable(
                                selected = isSel,
                                interactionSource = interaction,
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
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                            Icon(if (isSel) tab.selectedIcon else tab.icon, tab.label, tint = tint, modifier = Modifier.size(22.dp))
                            val badge = badges[i] ?: 0
                            if (badge > 0) Box(
                                Modifier.align(Alignment.TopEnd).offset(x = 9.dp, y = (-5).dp).size(17.dp).clip(CircleShape)
                                    .background(cs.error).border(1.5.dp, cs.surfaceContainerHigh, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) { Text(if (badge > 9) "9+" else "$badge", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold) }
                        }
                        Text(
                            tab.label, color = tint, fontSize = 11.sp, maxLines = 1,
                            fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                            style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
            }
        }
    }
}
