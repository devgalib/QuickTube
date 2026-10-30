package com.edureminder.quicktube.feature.gallery

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.edureminder.quicktube.core.common.Permissions
import com.edureminder.quicktube.core.designsystem.LocalQtExtras
import com.edureminder.quicktube.core.designsystem.PillButton
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.designsystem.pressable
import com.edureminder.quicktube.core.model.GalleryMedia
import com.edureminder.quicktube.core.ui.ConfirmDialog

enum class GalleryAccess { FULL, PARTIAL, NONE }

/** Current media access, re-checked whenever the screen resumes (the user may change it in Settings). */
@Composable
fun rememberGalleryAccess(): Pair<GalleryAccess, () -> Unit> {
    val context = LocalContext.current
    fun check() = when {
        Permissions.galleryFull(context) -> GalleryAccess.FULL
        Permissions.galleryPartial(context) -> GalleryAccess.PARTIAL
        else -> GalleryAccess.NONE
    }
    var access by remember { mutableStateOf(check()) }
    var asked by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { access = check() }
    LifecycleResumeEffect(Unit) {
        access = check()
        onPauseOrDispose { }
    }
    val request = {
        val activity = context as? Activity
        // After "Don't allow" twice Android stops showing the prompt; Settings is the only way left.
        val blocked = asked && activity != null && access == GalleryAccess.NONE &&
            Permissions.galleryRead.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        if (blocked) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        } else {
            asked = true
            launcher.launch(Permissions.galleryRead)
        }
    }
    return access to request
}

@Composable
fun GalleryPermissionPrompt(onAllow: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Column(modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(84.dp).clip(RoundedCornerShape(26.dp)).background(LocalQtExtras.current.brand), contentAlignment = Alignment.Center) {
            Icon(QtIcons.GalleryFilled, null, tint = Color.White, modifier = Modifier.size(42.dp))
        }
        Spacer(Modifier.height(22.dp))
        Text("See your photos and videos", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "QuickTube shows them here, grouped by month and folder. They stay on your phone.",
            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        PillButton("Allow access", onClick = onAllow, icon = QtIcons.Gallery)
    }
}

/** Android 14+ partial access: a slim banner offering to pick more or allow all. */
@Composable
fun PartialAccessBanner(onManage: () -> Unit, modifier: Modifier = Modifier, margin: androidx.compose.ui.unit.Dp = 16.dp) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().padding(horizontal = margin, vertical = 6.dp).clip(RoundedCornerShape(14.dp))
            .background(cs.surfaceContainerHigh).padding(start = 14.dp, end = 6.dp).height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(QtIcons.Info, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Text(
            "Showing only the photos you allowed", Modifier.weight(1f).padding(start = 10.dp),
            style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, maxLines = 1,
        )
        Text(
            "Manage", Modifier.clip(RoundedCornerShape(10.dp)).pressable(onClick = onManage).padding(horizontal = 10.dp, vertical = 8.dp),
            color = cs.primary, style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** "1 item selected" / "3 items selected", as the system gallery words it. */
fun selectedLabel(n: Int) = when (n) {
    0 -> "Select items"
    1 -> "1 item selected"
    else -> "$n items selected"
}

/**
 * Selection actions in the floating bar's place and shape. Delete goes through the system's trash dialog on
 * Android 11+, so it is undoable from the phone's own gallery for 30 days.
 */
@Composable
fun SelectionBar(
    selected: List<GalleryMedia>,
    allSelected: Boolean,
    onSelectAll: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val delete = rememberDeleteAction(onDone)
    val shape = RoundedCornerShape(28.dp)
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.widthIn(max = 440.dp).fillMaxWidth().height(68.dp)
                .shadow(6.dp, shape, ambientColor = Color.Black.copy(alpha = 0.4f), spotColor = Color.Black.copy(alpha = 0.4f))
                .clip(shape).background(cs.surfaceContainerHigh).border(1.dp, cs.outlineVariant.copy(alpha = 0.7f), shape)
                .padding(horizontal = 8.dp),
        ) {
            val enabled = selected.isNotEmpty()
            BarAction(QtIcons.Share, "Share", enabled) { GalleryActions.share(context, selected) }
            BarAction(QtIcons.SelectAll, if (allSelected) "Clear" else "Select all", true, onClick = onSelectAll)
            BarAction(QtIcons.Delete, "Delete", enabled) { delete(selected) }
        }
    }
}

@Composable
fun androidx.compose.foundation.layout.RowScope.BarAction(
    icon: ImageVector, label: String, enabled: Boolean, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit,
) {
    val color = if (enabled) tint else tint.copy(alpha = 0.38f)
    Column(
        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(20.dp)).pressable(enabled = enabled, scaleDown = 0.9f, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, label, tint = color, modifier = Modifier.size(23.dp))
        Text(label, color = color, fontSize = 11.sp, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 5.dp))
    }
}

/**
 * Returns a function that deletes media the platform-correct way: the system trash dialog (Android 11+), the
 * system's per-file prompt (Android 10) or our own confirmation plus storage permission (Android 9 and older).
 */
@Composable
fun rememberDeleteAction(onDeleted: () -> Unit): (List<GalleryMedia>) -> Unit {
    val context = LocalContext.current
    val done by rememberUpdatedState(onDeleted)
    var pending by remember { mutableStateOf<List<GalleryMedia>?>(null) }
    var confirm by remember { mutableStateOf<List<GalleryMedia>?>(null) }
    lateinit var run: (List<GalleryMedia>) -> Unit
    val sender = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val items = pending
        if (r.resultCode == Activity.RESULT_OK) {
            // Android 10 grants one file at a time; carry on with the rest.
            if (GalleryActions.needsOwnConfirm && items != null) run(items) else done()
        } else pending = null
    }
    val writePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) pending?.let { run(it) }
    }
    run = { items ->
        pending = items
        try {
            val request = GalleryActions.deleteRequest(context, items)
            if (request != null) sender.launch(IntentSenderRequest.Builder(request).build())
            else { pending = null; done() }
        } catch (_: SecurityException) {
            Toast.makeText(context, "Couldn't delete", Toast.LENGTH_SHORT).show()
        }
    }
    confirm?.let { items ->
        ConfirmDialog(
            title = if (items.size == 1) "Delete this item?" else "Delete ${items.size} items?",
            message = "They will be removed from your phone.",
            confirm = "Delete",
            onConfirm = {
                pending = items
                if (Permissions.granted(context, Permissions.storageWrite)) run(items) else writePermission.launch(Permissions.storageWrite)
            },
            onDismiss = { confirm = null },
        )
    }
    return { items ->
        if (items.isNotEmpty()) {
            if (GalleryActions.needsOwnConfirm) confirm = items else run(items)
        }
    }
}
