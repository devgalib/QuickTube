package com.edureminder.quicktube.feature.tools

import com.edureminder.quicktube.core.designsystem.QtIconButton
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.designsystem.icon.BrandIcons
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.edureminder.quicktube.core.common.Permissions
import com.edureminder.quicktube.core.designsystem.PillButton
import com.edureminder.quicktube.core.designsystem.QtTopBar
import com.edureminder.quicktube.core.ui.EmptyBox
import com.edureminder.quicktube.core.ui.LoadingBox
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun StatusSaverScreen(onBack: () -> Unit) {
    val vm = koinViewModel<StatusSaverViewModel>()
    val statuses by vm.statuses.collectAsStateWithLifecycle()
    val needsAccess by vm.needsAccess.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::onTreeGranted) }
    val readLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { vm.refresh() }
    LaunchedEffect(Unit) {
        // Android 10 and older read the WhatsApp folder directly and need storage permission first.
        if (Permissions.granted(context, Permissions.legacyRead)) vm.refresh() else readLauncher.launch(Permissions.legacyRead)
    }

    Scaffold(topBar = {
        Column {
            QtTopBar("WhatsApp status saver", onBack) { QtIconButton(QtIcons.Refresh, "Refresh", { vm.refresh() }) }
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Videos") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Images") })
            }
        }
    }) { pad ->
        val mod = Modifier.fillMaxSize().padding(pad)
        when {
            needsAccess -> Column(mod.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(BrandIcons.WhatsApp, null, Modifier.size(64.dp), tint = Color(0xFF25D366))
                Text("Allow access to WhatsApp statuses", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                Text(
                    "1. View statuses in WhatsApp first\n2. Tap the button below\n3. Tap “Use this folder”, then “Allow”",
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 16.dp),
                )
                PillButton("Grant access", onClick = {
                    picker.launch(if (Build.VERSION.SDK_INT >= 26) vm.initialTreeUri else null)
                }, modifier = Modifier.fillMaxWidth())
                Text("Open WhatsApp", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp).clickable {
                    context.packageManager.getLaunchIntentForPackage("com.whatsapp")?.let(context::startActivity)
                        ?: Toast.makeText(context, "WhatsApp is not installed", Toast.LENGTH_SHORT).show()
                })
            }
            loading && statuses.isEmpty() -> LoadingBox(mod)
            else -> {
                val list = statuses.filter { it.isVideo == (tab == 0) }
                if (list.isEmpty()) EmptyBox("No statuses yet", "Open WhatsApp and watch some statuses, then come back", mod, BrandIcons.WhatsApp)
                else LazyVerticalGrid(GridCells.Fixed(3), mod, contentPadding = PaddingValues(4.dp)) {
                    items(list, key = { it.uri.toString() }) { s ->
                        Box(Modifier.padding(4.dp).aspectRatio(0.75f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable {
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(s.uri, if (s.isVideo) "video/*" else "image/*")
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                }
                            }) {
                            AsyncImage(s.uri, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            if (s.isVideo) Icon(QtIcons.PlayCircle, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(32.dp))
                            IconButton(
                                onClick = { vm.save(s) { ok -> Toast.makeText(context, if (ok) "Saved to downloads" else "Couldn't save", Toast.LENGTH_SHORT).show() } },
                                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                            ) { Icon(QtIcons.Download, "Save", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp)) }
                        }
                    }
                }
            }
        }
    }
}
