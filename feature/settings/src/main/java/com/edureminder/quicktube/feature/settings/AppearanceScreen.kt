package com.edureminder.quicktube.feature.settings

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edureminder.quicktube.core.designsystem.QtCard
import com.edureminder.quicktube.core.designsystem.QtTopBar
import com.edureminder.quicktube.core.designsystem.accentPreview
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.designsystem.pressable
import com.edureminder.quicktube.core.model.ThemeAccent
import com.edureminder.quicktube.core.model.ThemeMode
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceScreen(onBack: () -> Unit) {
    val vm = koinViewModel<SettingsViewModel>()
    val s by vm.settings.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        QtTopBar("Appearance", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 24.dp)) {
            GroupLabel("Theme")
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark").forEach { (m, label) ->
                    ModeTile(label, m, s.themeMode == m, Modifier.weight(1f)) { vm.update { it.copy(themeMode = m) } }
                }
            }
            GroupLabel("Accent colour")
            QtCard {
                Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    ThemeAccent.entries.chunked(4).forEach { row ->
                        Row(Modifier.fillMaxWidth()) {
                            row.forEach { a ->
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    Swatch(a, selected = s.accent == a && !s.dynamicColor) { vm.update { it.copy(accent = a, dynamicColor = false) } }
                                }
                            }
                            repeat(4 - row.size) { Box(Modifier.weight(1f)) }
                        }
                    }
                }
            }
            GroupLabel("Options")
            QtCard {
                SwitchRow("Pure black", "True black backgrounds in dark mode, easy on OLED screens", s.amoled, QtIcons.DarkMode) { v ->
                    vm.update { it.copy(amoled = v) }
                }
                if (Build.VERSION.SDK_INT >= 31) {
                    RowDivider()
                    SwitchRow("Wallpaper colours", "Match the colours of your wallpaper (Material You)", s.dynamicColor, QtIcons.Palette) { v ->
                        vm.update { it.copy(dynamicColor = v) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ModeTile(label: String, mode: ThemeMode, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val border by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, label = "b")
    Column(modifier.pressable(scaleDown = 0.95f, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.78f).clip(RoundedCornerShape(18.dp))
                .border(if (selected) 2.5.dp else 1.dp, border, RoundedCornerShape(18.dp)),
        ) {
            // Mini preview of the app in that mode.
            val (bg, card) = when (mode) {
                ThemeMode.LIGHT -> Color(0xFFF7F8FA) to Color(0xFFE5E9EE)
                ThemeMode.DARK -> Color(0xFF0C0F14) to Color(0xFF1D232B)
                ThemeMode.SYSTEM -> Color(0xFF0C0F14) to Color(0xFF1D232B)
            }
            Box(Modifier.fillMaxSize().background(if (mode == ThemeMode.SYSTEM) Brush.linearGradient(0.5f to Color(0xFFF7F8FA), 0.5f to bg) else Brush.linearGradient(listOf(bg, bg))))
            Column(Modifier.padding(10.dp)) {
                Box(Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(8.dp)).background(card.copy(alpha = 0.9f)))
                Box(Modifier.padding(top = 6.dp).fillMaxWidth(0.7f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(card))
                Box(Modifier.padding(top = 4.dp).fillMaxWidth(0.5f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(card))
            }
            Box(
                Modifier.align(Alignment.BottomCenter).padding(8.dp).fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)),
            )
        }
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp),
            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun Swatch(accent: ThemeAccent, selected: Boolean, onClick: () -> Unit) {
    val (a, b) = accentPreview(accent)
    Column(Modifier.width(64.dp).pressable(scaleDown = 0.9f, haptic = true, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(52.dp).clip(CircleShape)
                .border(if (selected) 3.dp else 0.dp, if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
                .padding(if (selected) 5.dp else 0.dp).clip(CircleShape).background(Brush.linearGradient(listOf(a, b))),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(QtIcons.Check, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
        Text(accent.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
    }
}
