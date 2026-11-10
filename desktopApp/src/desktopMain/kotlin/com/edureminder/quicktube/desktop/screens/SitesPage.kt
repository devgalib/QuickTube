package com.edureminder.quicktube.desktop.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.edureminder.quicktube.core.common.Urls
import com.edureminder.quicktube.core.data.BrowserRepository
import com.edureminder.quicktube.core.designsystem.QtIconButton
import com.edureminder.quicktube.core.designsystem.QtTopBar
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.designsystem.pressable
import com.edureminder.quicktube.core.model.Site
import com.edureminder.quicktube.core.ui.BrandTile
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * The Sites list (the same one as the phone app's). On a computer there's no in-app browser: a site opens in the web
 * browser, and the video's copied link is downloaded from the Download tab (it notices copied links by itself).
 */
@Composable
fun SitesPage(onBack: () -> Unit, onOpen: (Site) -> Unit) {
    val repo = koinInject<BrowserRepository>()
    val sites by repo.sites.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        QtTopBar("Sites", onBack) { QtIconButton(QtIcons.Add, "Add a site", { adding = true }) }
        LazyVerticalGrid(
            GridCells.Adaptive(140.dp), Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(QtIcons.Info, null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        "Sites open in your web browser. Copy a video's link there, then come back: the Download tab offers to download it.",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
            items(sites, key = { it.id }) { site ->
                var menu by remember { mutableStateOf(false) }
                Column(
                    Modifier.clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .pressable(scaleDown = 0.96f, onClick = { onOpen(site) }).padding(vertical = 16.dp, horizontal = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BrandTile(site.name.ifBlank { site.url }, size = 52.dp, label = site.name)
                    Text(site.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
                    if (!site.builtIn) Row {
                        Text("Remove", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(6.dp)).pressable(onClick = { menu = true }).padding(4.dp))
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Remove ${site.name}") }, leadingIcon = { Icon(QtIcons.Delete, null) },
                                onClick = { menu = false; scope.launch { repo.deleteSites(listOf(site.id)) } })
                        }
                    }
                }
            }
        }
    }
    if (adding) AddSiteDialog(onDismiss = { adding = false }) { name, url -> scope.launch { repo.addSite(name, url) }; adding = false }
}

@Composable
private fun AddSiteDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    val ok = name.isNotBlank() && Urls.looksLikeUrl(url)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a site") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(url, { url = it.trim() }, label = { Text("Address") }, placeholder = { Text("example.com") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(enabled = ok, onClick = { onAdd(name.trim(), Urls.normalize(url)) }) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
