package com.edureminder.quicktube.navigation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.edureminder.quicktube.core.designsystem.LocalQtExtras
import com.edureminder.quicktube.core.designsystem.pressable
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class NavTab(val route: Any, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

/** How much wider the selected slot is than the others (1 + this, in slot weights). */
private const val Grow = 1.7f

/**
 * Floating dock: a dark capsule in both themes with icon-only tabs; the selected tab grows into a gradient pill that
 * shows its icon and label side by side. Each tab has its own 0..1 "expanded" spring, so on a tap the old tab shrinks
 * while the new one grows and the pill glides between them; it moves before navigation finishes.
 *
 * Nothing recomposes per frame: slot widths come from the springs in the layout phase, the pill is painted from them
 * in the draw phase, and icon/label fades are graphicsLayer alphas.
 */
@Composable
fun FloatingNavBar(
    tabs: List<NavTab>,
    selected: Int,
    onSelect: (Int) -> Unit,
    badges: Map<Int, Int> = emptyMap(),
) {
    val ex = LocalQtExtras.current
    val haptic = LocalHapticFeedback.current
    // Local selection so the pill moves on tap, before navigation finishes.
    var current by remember { mutableIntStateOf(selected.coerceAtLeast(0)) }
    LaunchedEffect(selected) { if (selected >= 0) current = selected }
    val expand = remember(tabs.size) { List(tabs.size) { i -> Animatable(if (i == current) 1f else 0f) } }
    LaunchedEffect(current) {
        val spec = spring<Float>(dampingRatio = 0.78f, stiffness = 420f)
        expand.forEachIndexed { i, a -> launch { a.animateTo(if (i == current) 1f else 0f, spec) } }
    }

    val shape = RoundedCornerShape(50)
    // Follows the theme: a dark dock in dark theme, a light one with a soft shadow in light theme; the selected tab keeps
    // the brand pill in both.
    val dock = if (ex.isDark) Color(0xFF24272F) else Color(0xFFFDFDFE)
    val rim = if (ex.isDark) Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.02f)))
    else Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.05f), Color.Black.copy(alpha = 0.09f)))
    val edges = remember(tabs.size) { FloatArray(tabs.size + 1) }

    Box(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Layout(
            content = {
                tabs.forEachIndexed { i, tab ->
                    Slot(
                        tab, badges[i] ?: 0, ring = if (i == current) Color.White else dock, progress = { expand[i].value },
                        selected = i == current,
                        onClick = {
                            if (current != i) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            current = i
                            onSelect(i)
                        },
                    )
                }
            },
            modifier = Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .height(64.dp)
                .shadow(18.dp, shape, ambientColor = Color.Black.copy(alpha = if (ex.isDark) 0.4f else 0.15f), spotColor = Color.Black.copy(alpha = if (ex.isDark) 0.7f else 0.22f))
                .clip(shape)
                .background(dock)
                .border(1.dp, rim, shape)
                .padding(6.dp)
                .drawBehind {
                    slotEdges(size.width, expand, edges)
                    var sum = 0f; var l = 0f; var r = 0f
                    expand.forEachIndexed { i, a ->
                        val e = a.value.coerceAtLeast(0f)
                        sum += e; l += e * edges[i]; r += e * edges[i + 1]
                    }
                    if (sum < 0.001f) return@drawBehind
                    l /= sum; r /= sum
                    val radius = CornerRadius(size.height / 2)
                    // A soft brand halo (a blurred shadow layer re-renders every frame and janks low-end GPUs), the gradient
                    // pill, then a top sheen for depth.
                    for ((spread, a) in listOf(5.dp to 0.08f, 2.5.dp to 0.14f)) {
                        val h = spread.toPx()
                        drawRoundRect(
                            ex.brandEnd.copy(alpha = a), topLeft = Offset(l - h, -h),
                            size = Size(r - l + 2 * h, size.height + 2 * h), cornerRadius = CornerRadius(size.height / 2 + h),
                        )
                    }
                    drawRoundRect(
                        Brush.linearGradient(listOf(ex.brandStart, ex.brandEnd), start = Offset(l, 0f), end = Offset(r, size.height)),
                        topLeft = Offset(l, 0f), size = Size(r - l, size.height), cornerRadius = radius,
                    )
                    drawRoundRect(
                        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.Transparent), endY = size.height * 0.55f),
                        topLeft = Offset(l, 0f), size = Size(r - l, size.height), cornerRadius = radius,
                    )
                },
        ) { measurables, c ->
            slotEdges(c.maxWidth.toFloat(), expand, edges)
            val placeables = measurables.mapIndexed { i, m ->
                val x0 = edges[i].roundToInt()
                m.measure(Constraints.fixed(edges[i + 1].roundToInt() - x0, c.maxHeight))
            }
            layout(c.maxWidth, c.maxHeight) { placeables.forEachIndexed { i, p -> p.place(edges[i].roundToInt(), 0) } }
        }
    }
}

/** Fills [out] with the slots' x edges for [width]: each slot weighs 1, plus [Grow] times its expansion. */
private fun slotEdges(width: Float, expand: List<Animatable<Float, *>>, out: FloatArray) {
    var total = 0f
    expand.forEach { total += 1f + Grow * it.value }
    out[0] = 0f
    expand.forEachIndexed { i, a -> out[i + 1] = out[i] + width * (1f + Grow * a.value) / total }
}

@Composable
private fun Slot(
    tab: NavTab,
    badge: Int,
    ring: Color,
    progress: () -> Float,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val muted = if (LocalQtExtras.current.isDark) Color.White.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurfaceVariant
    Layout(
        content = {
            Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                Icon(tab.icon, null, tint = muted, modifier = Modifier.size(24.dp).graphicsLayer { alpha = 1f - progress().coerceIn(0f, 1f) })
                Icon(tab.selectedIcon, null, tint = Color.White, modifier = Modifier.size(24.dp).graphicsLayer { alpha = progress().coerceIn(0f, 1f) })
                if (badge > 0) Box(
                    Modifier.align(Alignment.TopEnd).offset(x = 11.dp, y = (-9).dp).size(18.dp).clip(CircleShape)
                        .background(ring).padding(2.dp).clip(CircleShape).background(MaterialTheme.colorScheme.error),
                    contentAlignment = Alignment.Center,
                ) { Text(if (badge > 9) "9+" else "$badge", color = Color.White, fontSize = 9.sp, lineHeight = 9.sp, fontWeight = FontWeight.Bold) }
            }
            Text(
                tab.label, color = Color.White, maxLines = 1, softWrap = false,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, lineHeight = 16.sp, letterSpacing = 0.1.sp),
                fontWeight = FontWeight.Bold,
            )
        },
        modifier = Modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(50))
            .pressable(scaleDown = 0.9f, role = Role.Tab, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = tab.label
                role = Role.Tab
                this.selected = selected
            },
    ) { (iconM, labelM), c ->
        val icon = iconM.measure(Constraints())
        val label = labelM.measure(Constraints())
        val e = progress().coerceIn(0f, 1f)
        val gap = 8.dp.roundToPx()
        // The icon+label group stays centred while the label slides in beside the icon.
        val groupW = icon.width + e * (gap + label.width)
        val x = ((c.maxWidth - groupW) / 2f).roundToInt()
        layout(c.maxWidth, c.maxHeight) {
            icon.place(x, (c.maxHeight - icon.height) / 2)
            label.placeWithLayer(x + icon.width + gap, (c.maxHeight - label.height) / 2) {
                alpha = e * e
                translationX = (1f - e) * -6.dp.toPx()
            }
        }
    }
}
