package com.edureminder.quicktube.core.update

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edureminder.quicktube.core.common.Format
import com.edureminder.quicktube.core.designsystem.LocalQtExtras
import com.edureminder.quicktube.core.designsystem.PillButton
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.designsystem.pressable
import org.koin.compose.koinInject

/** Place once at the root: shows the "Update available" sheet and walks through download → permission → install. */
@Composable
fun UpdatePrompt() {
    val manager = koinInject<UpdateManager>()
    val visible by manager.promptVisible.collectAsStateWithLifecycle()
    val state by manager.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Coming back from Android's "Install unknown apps" screen continues the update by itself.
    LifecycleResumeEffect(Unit) {
        manager.onPermissionResult()
        onPauseOrDispose {}
    }
    if (!visible) return
    val info = when (val s = state) {
        is UpdateState.Available -> s.info
        is UpdateState.NeedsPermission -> s.info
        is UpdateState.Downloading -> s.info
        is UpdateState.Installing -> s.info
        is UpdateState.Failed -> s.info
        else -> null
    } ?: return
    val busy = state is UpdateState.Downloading || state is UpdateState.Installing
    ModalBottomSheet(
        onDismissRequest = { if (!busy) manager.later() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !busy }),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).background(LocalQtExtras.current.brand),
                    contentAlignment = Alignment.Center,
                ) { Icon(QtIcons.Download, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(30.dp)) }
                Column(Modifier.padding(start = 16.dp)) {
                    Text("Update available", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Version ${info.versionName}" + if (info.apkSize > 0) " · ${Format.bytes(info.apkSize)}" else "",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (info.notes.isNotBlank()) {
                Text("What's new", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
                Text(
                    info.notes, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp).clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh).verticalScroll(rememberScrollState()).padding(14.dp),
                )
            }
            Box(Modifier.padding(top = 22.dp)) {
                AnimatedContent(state, transitionSpec = { fadeIn() togetherWith fadeOut() }, contentKey = { it::class }, label = "update") { s ->
                    when (s) {
                        is UpdateState.Downloading -> Progress(
                            if (s.total > 0) s.bytes.toFloat() / s.total else 0f,
                            "Downloading… " + Format.bytes(s.bytes) + if (s.total > 0) " of ${Format.bytes(s.total)}" else "",
                        )
                        is UpdateState.Installing -> Progress(1f, "Installing… confirm in the Android dialog if asked")
                        is UpdateState.NeedsPermission -> Column {
                            Text(
                                "To install updates, Android needs you to allow QuickTube to install apps. Turn on " +
                                    "\"Allow from this source\" on the next screen, then come back. The update continues by itself.",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            PillButton("Allow installs", { context.startActivity(manager.permissionIntent()) },
                                Modifier.fillMaxWidth().padding(top = 16.dp), icon = QtIcons.Shield)
                            Later(manager)
                        }
                        is UpdateState.Failed -> Column {
                            Text(s.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                            PillButton("Try again", { manager.start(info) }, Modifier.fillMaxWidth().padding(top = 16.dp), icon = QtIcons.Refresh)
                            Later(manager)
                        }
                        else -> Column {
                            PillButton("Update now", { manager.start(info) }, Modifier.fillMaxWidth(), icon = QtIcons.Download)
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                                TextAction("Later") { manager.later() }
                                TextAction("Skip this version") { manager.skip(info) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Progress(fraction: Float, label: String) {
    Column {
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).clip(RoundedCornerShape(50)).background(LocalQtExtras.current.brand))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 10.dp).fillMaxWidth(), textAlign = TextAlign.Center)
    }
}

@Composable
private fun Later(manager: UpdateManager) =
    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) { TextAction("Later") { manager.later() } }

@Composable
private fun TextAction(text: String, onClick: () -> Unit) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).pressable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
