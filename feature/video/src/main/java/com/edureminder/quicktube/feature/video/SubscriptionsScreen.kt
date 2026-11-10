package com.edureminder.quicktube.feature.video

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import com.edureminder.quicktube.core.designsystem.QtChip
import com.edureminder.quicktube.core.model.Subscription
import java.text.DateFormat
import java.util.Date
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.edureminder.quicktube.core.data.SubscriptionRepository
import com.edureminder.quicktube.core.designsystem.QtTopBar
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.designsystem.pressable
import com.edureminder.quicktube.core.downloadsheet.DownloadSheetController
import com.edureminder.quicktube.core.model.MediaItem
import com.edureminder.quicktube.core.ui.Avatar
import com.edureminder.quicktube.core.ui.EmptyBox
import com.edureminder.quicktube.core.ui.ErrorBox
import com.edureminder.quicktube.core.ui.LoadingBox
import com.edureminder.quicktube.core.ui.MediaRow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

data class SubscriptionsUi(val items: List<MediaItem>? = null, val refreshing: Boolean = false, val error: String? = null)

class SubscriptionsViewModel(private val repo: SubscriptionRepository) : ViewModel() {
    val channels = repo.subscriptions.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    private val _ui = MutableStateFlow(SubscriptionsUi())
    val ui: StateFlow<SubscriptionsUi> = _ui

    fun unsubscribe(url: String) = viewModelScope.launch { repo.unsubscribe(url) }

    fun load(refresh: Boolean = false) {
        if (!refresh && _ui.value.items != null) return
        _ui.value = _ui.value.copy(refreshing = refresh, error = null)
        viewModelScope.launch {
            _ui.value = runCatching { repo.feed() }.fold(
                { SubscriptionsUi(items = it) },
                { SubscriptionsUi(items = _ui.value.items, error = it.message ?: "Couldn't load new videos") },
            )
        }
    }
}

/**
 * Channels followed on this phone: their newest videos together (Videos), or the channels themselves to visit or
 * unfollow (Channels). Each channel is kept by its URL, so its page opens straight from here.
 */
@Composable
fun SubscriptionsScreen(onBack: () -> Unit, onOpen: (MediaItem) -> Unit, onChannel: (String) -> Unit) {
    val vm = koinViewModel<SubscriptionsViewModel>()
    val channels by vm.channels.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val sheet = koinInject<DownloadSheetController>()
    var showChannels by rememberSaveable { mutableStateOf(false) }
    var leaving by remember { mutableStateOf<Subscription?>(null) }
    LaunchedEffect(channels?.size) { if (channels != null) vm.load(refresh = ui.items != null) }
    Scaffold(topBar = {
        Column {
            QtTopBar("Subscriptions", onBack, subtitle = channels?.let { "${it.size} channels · saved on this phone" })
            if (!channels.isNullOrEmpty()) Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QtChip("Videos", !showChannels, { showChannels = false })
                QtChip("Channels", showChannels, { showChannels = true })
            }
        }
    }) { pad ->
        val mod = Modifier.fillMaxSize().padding(pad)
        val list = channels
        when {
            list == null -> LoadingBox(mod)
            list.isEmpty() -> EmptyBox("No subscriptions yet", "Open a channel and tap Subscribe. Its new videos show up here.", mod, QtIcons.Subscriptions)
            showChannels -> LazyColumn(mod, contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)) {
                items(list.sortedBy { it.name.lowercase() }, key = { it.url }) { c ->
                    ChannelRow(c, onOpen = { onChannel(c.url) }, onUnsubscribe = { leaving = c })
                }
            }
            ui.items == null && ui.error == null -> LoadingBox(mod)
            ui.items == null -> ErrorBox(ui.error!!, { vm.load(true) }, mod)
            else -> PullToRefreshBox(ui.refreshing, onRefresh = { vm.load(true) }, mod) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                    item(key = "channels") {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(list, key = { it.url }) { c ->
                                Column(Modifier.width(68.dp).pressable(scaleDown = 0.94f, onClick = { onChannel(c.url) }), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Avatar(c.avatar, 56)
                                    Text(c.name, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                    }
                    if (ui.items!!.isEmpty()) item(key = "none") { EmptyBox("No videos yet", "Pull down to check again", Modifier.fillMaxWidth().padding(top = 40.dp)) }
                    items(ui.items!!, key = { it.url }) { v -> Box { MediaRow(v, onClick = { onOpen(v) }, onDownload = { sheet.open(v) }) } }
                }
            }
        }
    }
    leaving?.let { c ->
        AlertDialog(
            onDismissRequest = { leaving = null },
            title = { Text("Unsubscribe from ${c.name}?") },
            text = { Text("Its new videos won't show up here any more.") },
            confirmButton = { TextButton(onClick = { vm.unsubscribe(c.url); leaving = null }) { Text("Unsubscribe") } },
            dismissButton = { TextButton(onClick = { leaving = null }) { Text("Cancel") } },
        )
    }
}

/** A followed channel: tap to open its page; the button unfollows it. */
@Composable
private fun ChannelRow(c: Subscription, onOpen: () -> Unit, onUnsubscribe: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().pressable(scaleDown = 0.99f, role = null, onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(c.avatar, 48)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(c.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "Since " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(c.subscribedAt)),
                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "Unsubscribe", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(cs.surfaceContainerHigh)
                .pressable(scaleDown = 0.94f, onClick = onUnsubscribe).padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}
