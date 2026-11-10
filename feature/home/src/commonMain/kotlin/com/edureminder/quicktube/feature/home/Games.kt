package com.edureminder.quicktube.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.edureminder.quicktube.core.data.GamesRepository
import com.edureminder.quicktube.core.designsystem.LocalQtExtras
import com.edureminder.quicktube.core.designsystem.QtTopBar
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.designsystem.pressable
import com.edureminder.quicktube.core.model.PromoGame
import com.edureminder.quicktube.core.ui.EmptyBox
import org.koin.compose.viewmodel.koinViewModel

class GamesViewModel(private val repo: GamesRepository) : ViewModel() {
    val games = repo.games
    init { repo.refreshIfDue() }
}

/** Opens the game's store page (Android: the Play Store app when there is one; it shows "Open" when it's installed). */
@Composable
internal expect fun rememberGameOpener(): (PromoGame) -> Unit

/** The home screen's games row: tiles like the media rows around it, the game's logo with its name and genre. */
@Composable
internal fun GamesRow(games: List<PromoGame>) {
    val openGame = rememberGameOpener()
    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(games, key = { it.id }) { g -> GameTile(g) { openGame(g) } }
    }
}

@Composable
private fun GameTile(game: PromoGame, onClick: () -> Unit) {
    Column(Modifier.width(160.dp).pressable(scaleDown = 0.96f, role = null, onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            Icon(QtIcons.Apps, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Center))
            AsyncImage(game.iconUrl, game.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.35f)))))
            PlayChip(Modifier.align(Alignment.BottomStart).padding(8.dp), small = true)
        }
        Text(game.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
        Text(listOf(game.genre, "Free").filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
}

/** "Play" pill in the brand gradient. */
@Composable
private fun PlayChip(modifier: Modifier = Modifier, small: Boolean = false) {
    Row(
        modifier.clip(CircleShape).background(LocalQtExtras.current.brand)
            .padding(horizontal = if (small) 10.dp else 16.dp, vertical = if (small) 5.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(QtIcons.Play, null, tint = Color.White, modifier = Modifier.size(if (small) 14.dp else 18.dp))
        Text("Play", color = Color.White, style = if (small) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp))
    }
}

/** See all: every game as a big card (screenshot banner, logo, name, tagline). */
@Composable
fun GamesScreen(onBack: () -> Unit) {
    val vm = koinViewModel<GamesViewModel>()
    val section by vm.games.collectAsStateWithLifecycle()
    val openGame = rememberGameOpener()
    Column(Modifier.fillMaxSize()) {
        QtTopBar(section.title, onBack, subtitle = "Made by the QuickTube team")
        if (section.games.isEmpty()) EmptyBox("No games yet", "New games show up here", icon = QtIcons.Apps)
        else LazyColumn(
            Modifier.fillMaxSize().navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            items(section.games, key = { it.id }) { g -> GameCard(g, Modifier.animateItem()) { openGame(g) } }
        }
    }
}

@Composable
private fun GameCard(game: PromoGame, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)
            .pressable(scaleDown = 0.98f, role = null, onClick = onClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            AsyncImage(game.bannerUrl ?: game.iconUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                game.iconUrl, game.name, contentScale = ContentScale.Crop,
                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(game.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (game.tagline.isNotBlank()) Text(game.tagline, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (game.genre.isNotBlank()) Text(game.genre, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
            }
            PlayChip()
        }
    }
}
