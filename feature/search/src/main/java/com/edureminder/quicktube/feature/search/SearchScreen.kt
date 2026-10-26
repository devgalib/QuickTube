package com.edureminder.quicktube.feature.search

import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import com.edureminder.quicktube.core.designsystem.QtChip
import com.edureminder.quicktube.core.designsystem.QtIconButton
import com.edureminder.quicktube.core.designsystem.pressable
import com.edureminder.quicktube.core.ui.BrandTile
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.edureminder.quicktube.core.common.Urls
import com.edureminder.quicktube.core.designsystem.SectionHeader
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    initial: String,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onViewSites: () -> Unit,
    onDownloadUrl: (String) -> Unit = onOpenUrl,
) {
    val vm = koinViewModel<SearchViewModel>()
    var field by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(field.text) { vm.query.value = field.text }

    fun submit(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        if (Urls.looksLikeUrl(t)) onDownloadUrl(Urls.normalize(t))
        else {
            vm.remember(t)
            onSearch(t)
        }
    }

    Scaffold(topBar = {
        Row(Modifier.statusBarsPadding().padding(start = 6.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            QtIconButton(QtIcons.ArrowBack, "Back", onBack)
            TextField(
                value = field,
                onValueChange = { field = it },
                placeholder = { Text("Search or paste link") },
                leadingIcon = { Icon(QtIcons.Search, null, Modifier.size(20.dp)) },
                singleLine = true,
                modifier = Modifier.weight(1f).focusRequester(focus),
                shape = RoundedCornerShape(50),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
                trailingIcon = {
                    if (field.text.isNotEmpty()) QtIconButton(QtIcons.Close, "Clear", { field = TextFieldValue("") }, size = 36.dp, iconSize = 18.dp)
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit(field.text) }),
            )
            QtIconButton(QtIcons.Download, "Go", { submit(field.text) }, tint = MaterialTheme.colorScheme.onPrimary,
                container = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
        }
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            val text = field.text.trim()
            if (text.isNotEmpty()) {
                if (Urls.looksLikeUrl(text)) item {
                    SuggestionRow(QtIcons.Language, "Open ${Urls.normalize(text)}", onClick = { submit(text) })
                }
                items(suggestions) { s ->
                    SuggestionRow(QtIcons.Search, s, onClick = { submit(s) }, onFill = { field = TextFieldValue(s, TextRange(s.length)) })
                }
            } else {
                if (recent.isNotEmpty()) {
                    item {
                        SectionHeader("Recent") { TextButton(onClick = { vm.clearHistory() }) { Text("Clear") } }
                    }
                    items(recent) { q ->
                        SuggestionRow(QtIcons.History, q, onClick = { submit(q) }, onRemove = { vm.forget(q) })
                    }
                }
                item { SectionHeader("Hot searches") }
                item {
                    FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        vm.hot.forEachIndexed { i, h ->
                            QtChip(h, false, { submit(h) }, icon = if (i < 3) QtIcons.TrendingUp else null)
                        }
                    }
                }
                item {
                    SectionHeader("View sites") { TextButton(onClick = onViewSites) { Text("View all") } }
                }
                item {
                    FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "YouTube" to "https://m.youtube.com/", "Instagram" to "https://www.instagram.com/",
                            "Facebook" to "https://m.facebook.com/", "TikTok" to "https://www.tiktok.com/", "Twitter/X" to "https://x.com/",
                        ).forEach { (n, u) ->
                            Row(
                                Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                    .pressable(scaleDown = 0.94f, onClick = { onOpenUrl(u) }).padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                BrandTile(n, size = 26.dp)
                                Text(n, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit,
    onFill: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().pressable(scaleDown = 0.985f, onClick = onClick).padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        Text(text, Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyLarge)
        if (onFill != null) QtIconButton(QtIcons.NorthWest, "Fill", onFill, tint = MaterialTheme.colorScheme.onSurfaceVariant, iconSize = 18.dp)
        if (onRemove != null) QtIconButton(QtIcons.Close, "Remove", onRemove, tint = MaterialTheme.colorScheme.onSurfaceVariant, iconSize = 18.dp)
    }
}
