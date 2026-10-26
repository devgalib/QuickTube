package com.edureminder.quicktube.feature.tools

import com.edureminder.quicktube.core.designsystem.QtIconButton
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edureminder.quicktube.core.common.Format
import com.edureminder.quicktube.core.designsystem.QtTopBar
import com.edureminder.quicktube.core.model.ContentType
import com.edureminder.quicktube.core.model.DownloadTask
import com.edureminder.quicktube.core.ui.EmptyBox
import com.edureminder.quicktube.core.ui.LoadingBox
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun VaultScreen(onBack: () -> Unit, onOpenPlayer: () -> Unit) {
    val vm = koinViewModel<VaultViewModel>()
    val pin by vm.pin.collectAsStateWithLifecycle()
    val unlocked by vm.unlocked.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { onDispose { vm.lock() } }
    Scaffold(topBar = { QtTopBar("Vault", onBack) }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                pin == "loading" -> LoadingBox()
                pin == null -> PinPad("Create a 4-digit PIN", confirmStep = true) { vm.setPin(it); true }
                !unlocked -> PinPad("Enter PIN") { vm.tryUnlock(it) }
                else -> VaultContent(vm, onOpenPlayer)
            }
        }
    }
}

@Composable
private fun VaultContent(vm: VaultViewModel, onOpenPlayer: () -> Unit) {
    val items by vm.items.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf<DownloadTask?>(null) }
    val types = listOf(ContentType.VIDEO, ContentType.AUDIO, ContentType.IMAGE)
    Column(Modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = tab) {
            listOf("Video", "Music", "Photo").forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) }
        }
        val list = items.filter { it.type == types[tab] || (tab == 2 && it.type == ContentType.OTHER) }
        if (list.isEmpty()) EmptyBox("Keep your private files here", "Files in Vault won't be seen in Play tab.\nUse “Lock into Vault” from a file's menu.",
            Modifier.fillMaxSize(), QtIcons.Lock)
        else LazyColumn(Modifier.fillMaxSize()) {
            items(list, key = { it.id }) { t ->
                Row(
                    Modifier.fillMaxWidth().combinedClickable(onClick = {
                        if (t.type == ContentType.VIDEO || t.type == ContentType.AUDIO) { vm.play(t); onOpenPlayer() } else menu = t
                    }, onLongClick = { menu = t }).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TaskThumb(t, Modifier.width(110.dp).aspectRatio(16f / 9f))
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(t.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(Format.bytes(t.totalBytes), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    QtIconButton(QtIcons.LockOpen, "Move out of vault", { vm.unlockItems(listOf(t.id)) })
                }
            }
        }
    }
    menu?.let { t ->
        AlertDialog(
            onDismissRequest = { menu = null },
            title = { Text(t.title, maxLines = 2) },
            text = { Text("Move this file back to the Play tab, or delete it?") },
            confirmButton = { TextButton(onClick = { vm.unlockItems(listOf(t.id)); menu = null }) { Text("Unlock") } },
            dismissButton = {
                TextButton(onClick = { vm.delete(listOf(t.id)); menu = null }) {
                    Icon(QtIcons.Delete, null); Text("Delete")
                }
            },
        )
    }
}

/** Numeric keypad; with [confirmStep] asks for the PIN twice. Returns false from [onComplete] to shake/reset. */
@Composable
fun PinPad(title: String, confirmStep: Boolean = false, onComplete: (String) -> Boolean) {
    var entered by remember { mutableStateOf("") }
    var first by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun press(d: String) {
        if (entered.length >= 4) return
        entered += d
        error = null
        if (entered.length == 4) {
            if (confirmStep && first == null) { first = entered; entered = "" }
            else if (confirmStep && first != entered) { error = "PINs don't match"; first = null; entered = "" }
            else if (!onComplete(entered)) { error = "Wrong PIN"; entered = "" }
        }
    }
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(QtIcons.Lock, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(if (confirmStep && first != null) "Confirm your PIN" else title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
        Text(error ?: " ", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        Row(Modifier.padding(vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            repeat(4) { i ->
                Box(Modifier.size(16.dp).clip(CircleShape).background(if (i < entered.length) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant))
            }
        }
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "<")).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                row.forEach { k ->
                    Box(
                        Modifier.size(72.dp).clip(CircleShape)
                            .background(if (k.isEmpty()) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh)
                            .combinedClickable(enabled = k.isNotEmpty(), onClick = { if (k == "<") entered = entered.dropLast(1) else press(k) }),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (k == "<") Icon(QtIcons.Backspace, "Delete")
                        else Text(k, fontSize = 26.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}
