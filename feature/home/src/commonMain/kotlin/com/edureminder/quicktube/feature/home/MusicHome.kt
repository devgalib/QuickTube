package com.edureminder.quicktube.feature.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.edureminder.quicktube.core.common.Format
import com.edureminder.quicktube.core.data.MediaRepository
import com.edureminder.quicktube.core.designsystem.LocalBottomBarHeight
import com.edureminder.quicktube.core.designsystem.LocalQtExtras
import com.edureminder.quicktube.core.designsystem.QtChip
import com.edureminder.quicktube.core.designsystem.icon.QtIcons
import com.edureminder.quicktube.core.designsystem.pressable
import com.edureminder.quicktube.core.model.ItemKind
import com.edureminder.quicktube.core.model.MediaItem
import com.edureminder.quicktube.core.model.SearchFilter
import com.edureminder.quicktube.core.ui.ErrorBox
import com.edureminder.quicktube.core.ui.Thumbnail
import com.edureminder.quicktube.core.ui.TilesSkeleton
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/**
 * How a Music shelf is drawn, after Flow: Speed dial tiles (cover flush left), Quick picks rows grouped in cards,
 * big square covers, artists in Material's cookie shape, and playlist covers.
 */
enum class ShelfStyle { SPEED_DIAL, QUICK_PICKS, SONGS, ARTISTS, PLAYLISTS }

data class MusicShelf(val title: String, val items: List<MediaItem>, val style: ShelfStyle)

/** A mood chip and the YouTube Music searches its shelves come from (song search, playlist search). */
data class Mood(val label: String, val shelves: List<Triple<String, String, ShelfStyle>>)

private val moods = listOf(
    Mood("For you", listOf(
        Triple("Speed dial", "top hits", ShelfStyle.SPEED_DIAL),
        Triple("Quick picks", "trending songs", ShelfStyle.QUICK_PICKS),
        // Built from the artists of the songs above (see MusicViewModel).
        Triple("Artists for you", "", ShelfStyle.ARTISTS),
        Triple("Rediscover", "best songs of all time", ShelfStyle.SONGS),
        Triple("Featured playlists", "top hits playlist", ShelfStyle.PLAYLISTS),
        Triple("New releases", "new songs", ShelfStyle.SONGS),
        Triple("Chill & lofi", "lofi chill songs", ShelfStyle.SONGS),
    )),
    Mood("Relax", listOf(Triple("Quick picks", "relaxing songs", ShelfStyle.QUICK_PICKS), Triple("Calm playlists", "relaxing music playlist", ShelfStyle.PLAYLISTS), Triple("Acoustic", "acoustic songs", ShelfStyle.SONGS))),
    Mood("Energize", listOf(Triple("Quick picks", "energetic songs", ShelfStyle.QUICK_PICKS), Triple("Upbeat playlists", "upbeat playlist", ShelfStyle.PLAYLISTS), Triple("Dance hits", "dance hits", ShelfStyle.SONGS))),
    Mood("Workout", listOf(Triple("Quick picks", "workout songs", ShelfStyle.QUICK_PICKS), Triple("Gym playlists", "workout music playlist", ShelfStyle.PLAYLISTS), Triple("Running", "running songs", ShelfStyle.SONGS))),
    Mood("Romance", listOf(Triple("Quick picks", "romantic songs", ShelfStyle.QUICK_PICKS), Triple("Love playlists", "romantic songs playlist", ShelfStyle.PLAYLISTS), Triple("Love ballads", "love ballads", ShelfStyle.SONGS))),
    Mood("Sad", listOf(Triple("Quick picks", "sad songs", ShelfStyle.QUICK_PICKS), Triple("Heartbreak playlists", "sad songs playlist", ShelfStyle.PLAYLISTS), Triple("Slow & emotional", "emotional songs", ShelfStyle.SONGS))),
    Mood("Party", listOf(Triple("Quick picks", "party songs", ShelfStyle.QUICK_PICKS), Triple("Party playlists", "party playlist", ShelfStyle.PLAYLISTS), Triple("Club hits", "club hits", ShelfStyle.SONGS))),
    Mood("Focus", listOf(Triple("Quick picks", "focus music", ShelfStyle.QUICK_PICKS), Triple("Study playlists", "study music playlist", ShelfStyle.PLAYLISTS), Triple("Instrumental", "instrumental music", ShelfStyle.SONGS))),
    Mood("Bollywood", listOf(Triple("Quick picks", "bollywood hits", ShelfStyle.QUICK_PICKS), Triple("Bollywood playlists", "bollywood playlist", ShelfStyle.PLAYLISTS), Triple("Arijit Singh", "arijit singh songs", ShelfStyle.SONGS))),
    Mood("Bangla", listOf(Triple("Quick picks", "bangla songs", ShelfStyle.QUICK_PICKS), Triple("Bangla playlists", "bangla song playlist", ShelfStyle.PLAYLISTS), Triple("Bangla band", "bangla band songs", ShelfStyle.SONGS))),
)

data class MusicUiState(
    val mood: Int = 0,
    val shelves: List<MusicShelf> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
)

class MusicViewModel(private val media: MediaRepository) : ViewModel() {
    private val _ui = MutableStateFlow(MusicUiState())
    val ui: StateFlow<MusicUiState> = _ui
    /** Shelves already loaded per mood, so switching chips back is instant. */
    private val cache = mutableMapOf<Int, List<MusicShelf>>()
    private var job: Job? = null

    /** "Artists for you": the artists of the songs shown, each looked up as an artist (their own channel and picture). */
    private suspend fun withArtists(mood: Int, shelves: List<MusicShelf>): List<MusicShelf> {
        val at = moods[mood].shelves.indexOfFirst { it.third == ShelfStyle.ARTISTS }
        if (at < 0) return shelves
        val names = shelves.filter { it.style != ShelfStyle.PLAYLISTS }.flatMap { s -> s.items.map { it.uploader } }
            .map { it.removeSuffix(" - Topic").substringBefore(",").substringBefore(" & ").substringBefore(" x ").trim() }
            // Compilation channels ("Top Hits 2023", "Lofi Music") aren't artists.
            .filter { it.length in 2..40 && !Regex("""(?i)\b(top|hits?|songs?|playlists?|music|mix|lofi|official|records?|vevo)\b|\d{4}""").containsMatchIn(it) }
            .distinctBy { it.lowercase() }.take(10)
        val artists = coroutineScope {
            // Only results that are that artist (same name), not a channel that merely mentions it.
            names.map { n ->
                async {
                    runCatching { media.search(n, SearchFilter.MUSIC_ARTISTS).items.firstOrNull { it.kind == ItemKind.CHANNEL && it.title.equals(n, ignoreCase = true) } }.getOrNull()
                        ?.let { it.copy(thumbnail = musicArt(it.thumbnail, it.url)) }
                }
            }.awaitAll().filterNotNull().distinctBy { it.url }
        }
        if (artists.size < 3) return shelves
        val title = moods[mood].shelves[at].first
        // Same place as in the mood's list (after the shelves before it that loaded).
        val before = moods[mood].shelves.take(at).count { m -> shelves.any { it.title == m.first } }
        return shelves.toMutableList().apply { add(before, MusicShelf(title, artists, ShelfStyle.ARTISTS)) }
    }

    fun ensure() { if (_ui.value.shelves.isEmpty() && !_ui.value.loading) select(_ui.value.mood) }

    fun select(mood: Int, refresh: Boolean = false) {
        job?.cancel()
        if (refresh) cache.remove(mood)
        cache[mood]?.let { _ui.value = MusicUiState(mood, it); return }
        _ui.update { MusicUiState(mood, if (refresh) it.shelves else emptyList(), loading = !refresh, refreshing = refresh) }
        job = viewModelScope.launch {
            val shelves = coroutineScope {
                moods[mood].shelves.filter { it.third != ShelfStyle.ARTISTS }.map { (title, query, style) ->
                    async {
                        val filter = when (style) {
                            ShelfStyle.PLAYLISTS -> SearchFilter.PLAYLISTS
                            ShelfStyle.ARTISTS -> SearchFilter.MUSIC_ARTISTS
                            else -> SearchFilter.MUSIC
                        }
                        runCatching { media.search(query, filter).items }.getOrNull()
                            // Songs, not hour-long mixes or live radio.
                            ?.filter {
                                when (style) {
                                    ShelfStyle.PLAYLISTS -> it.kind == ItemKind.PLAYLIST
                                    ShelfStyle.ARTISTS -> it.kind == ItemKind.CHANNEL
                                    else -> it.kind == ItemKind.VIDEO && !it.isLive && it.durationSec <= 600
                                }
                            }
                            ?.map { it.copy(thumbnail = musicArt(it.thumbnail, it.url)) }
                            ?.take(if (style == ShelfStyle.QUICK_PICKS || style == ShelfStyle.SPEED_DIAL) 17 else 12)
                            ?.takeIf { it.isNotEmpty() }?.let { MusicShelf(title, it, style) }
                    }
                }.awaitAll().filterNotNull()
            }.let { songs -> withArtists(mood, songs) }
            if (shelves.isNotEmpty()) cache[mood] = shelves
            _ui.value = MusicUiState(mood, shelves, error = if (shelves.isEmpty()) "Couldn't load music. Check your connection." else null)
        }
    }
}

/** The Music tab, after Flow's: mood chips, a Quick picks grid of songs, then shelves of songs and playlists. */
@Composable
internal fun MusicHome(actions: HomeActions, onDownload: (MediaItem) -> Unit) {
    val vm = koinViewModel<MusicViewModel>()
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.ensure() }
    Column(Modifier.fillMaxSize()) {
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(moods) { i, m -> QtChip(m.label, ui.mood == i, { vm.select(i) }) }
        }
        AnimatedContent(ui.mood to ui.loading, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "mood") { (_, loading) ->
            when {
                loading -> TilesSkeleton()
                ui.error != null && ui.shelves.isEmpty() -> ErrorBox(ui.error!!, { vm.select(ui.mood, refresh = true) })
                else -> PullToRefreshBox(ui.refreshing, onRefresh = { vm.select(ui.mood, refresh = true) }, Modifier.fillMaxSize()) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp + LocalBottomBarHeight.current)) {
                        item(key = "mine") { MyPlaylists(actions) }
                        items(ui.shelves, key = { it.title }) { shelf ->
                            Column(Modifier.padding(top = 18.dp)) {
                                ShelfHeader(shelf, onPlayAll = { if (shelf.style.playable) actions.playMusic(shelf.items, 0) })
                                when (shelf.style) {
                                    ShelfStyle.SPEED_DIAL -> SpeedDial(shelf.items, onPlay = { actions.playMusic(shelf.items, it) },
                                        onShuffle = { actions.playMusic(shelf.items.shuffled(), 0) })
                                    ShelfStyle.ARTISTS -> LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        items(shelf.items, key = { it.url }) { item -> ArtistCard(item) { actions.openItem(item) } }
                                    }
                                    ShelfStyle.QUICK_PICKS -> QuickPicks(shelf.items, onPlay = { actions.playMusic(shelf.items, it) }, onDownload = onDownload)
                                    ShelfStyle.SONGS -> LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                        itemsIndexed(shelf.items, key = { _, it -> it.url }) { i, item -> SongCard(item, { actions.playMusic(shelf.items, i) }, { onDownload(item) }) }
                                    }
                                    ShelfStyle.PLAYLISTS -> LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                        items(shelf.items, key = { it.url }) { item -> PlaylistCard(item) { actions.openItem(item) } }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShelfHeader(shelf: MusicShelf, onPlayAll: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(shelf.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
        if (shelf.style.playable && shelf.style != ShelfStyle.SPEED_DIAL) Row(
            Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .pressable(scaleDown = 0.92f, onClick = onPlayAll).padding(start = 10.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(QtIcons.Play, null, modifier = Modifier.size(18.dp))
            Text("Play all", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

/** Four song rows per column, scrolling sideways with the next column peeking in (YouTube Music's Quick picks). */
@Composable
private fun QuickPicks(songs: List<MediaItem>, onPlay: (Int) -> Unit, onDownload: (MediaItem) -> Unit) {
    BoxWithConstraints {
        // Most of a phone's width; on tablets and desktop windows several columns sit side by side.
        val column = minOf(maxWidth * 0.86f, 420.dp)
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp)) {
            items(songs.indices.chunked(4), key = { it.first() }) { group ->
                // Each column's rows sit together in one rounded card, like Flow's grouped Quick picks.
                Column(
                    Modifier.width(column).padding(horizontal = 4.dp).clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer).padding(vertical = 4.dp),
                ) {
                    group.forEach { i -> SongRow(songs[i], { onPlay(i) }, { onDownload(songs[i]) }) }
                }
            }
        }
    }
}

@Composable
private fun SongRow(item: MediaItem, onPlay: () -> Unit, onDownload: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).pressable(scaleDown = 0.98f, role = null, onClick = onPlay).padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(item.thumbnail, Modifier.size(54.dp), corner = 8)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOf(item.uploader, Format.duration(item.durationSec).takeIf { item.durationSec > 0 }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.size(40.dp).clip(CircleShape).pressable(scaleDown = 0.85f, onClick = onDownload), contentAlignment = Alignment.Center) {
            Icon(QtIcons.Download, "Download", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

/** Square cover, a play badge, title and artist below. */
@Composable
private fun SongCard(item: MediaItem, onPlay: () -> Unit, onDownload: () -> Unit) {
    val brand = LocalQtExtras.current.brand
    Column(Modifier.width(168.dp).pressable(scaleDown = 0.96f, role = null, onClick = onPlay)) {
        Thumbnail(item.thumbnail, Modifier.fillMaxWidth().aspectRatio(1f), corner = 20) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.45f))))
            Box(Modifier.align(Alignment.BottomEnd).padding(8.dp).size(36.dp).clip(CircleShape).background(brand), contentAlignment = Alignment.Center) {
                Icon(QtIcons.Play, "Play", tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(32.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.4f))
                    .pressable(scaleDown = 0.85f, onClick = onDownload),
                contentAlignment = Alignment.Center,
            ) { Icon(QtIcons.Download, "Download", tint = Color.White, modifier = Modifier.size(16.dp)) }
        }
        Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(item.uploader, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PlaylistCard(item: MediaItem, onClick: () -> Unit) {
    Column(Modifier.width(200.dp).pressable(scaleDown = 0.96f, role = null, onClick = onClick)) {
        Thumbnail(item.thumbnail, Modifier.fillMaxWidth().height(200.dp), corner = 18) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.65f))))
            Row(Modifier.align(Alignment.BottomStart).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(QtIcons.PlaylistPlay, null, tint = Color.White, modifier = Modifier.size(20.dp))
                if (item.streamCount > 0) Text("${item.streamCount} songs", color = Color.White, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 6.dp))
            }
        }
        Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        if (item.uploader.isNotBlank()) Text(item.uploader, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val ShelfStyle.playable get() = this != ShelfStyle.PLAYLISTS && this != ShelfStyle.ARTISTS

/**
 * Song art at a size that stays sharp on big cards and never shows YouTube's black bars: YouTube Music's square art
 * asked for at 544 px, and video frames taken from the 16:9 HD thumbnail (cropped square by the card) instead of the
 * 4:3 one with bars.
 */
internal fun musicArt(url: String?, pageUrl: String): String? {
    val u = url ?: return null
    if ("googleusercontent.com" in u || "ggpht.com" in u) return u.replace(Regex("""=w\d+-h\d+[^/?]*$|=s\d+[^/?]*$"""), "") + "=w544-h544-l90-rj"
    // The video id from the page, or from the thumbnail's own address (".../vi/<id>/hqdefault.jpg").
    val id = com.edureminder.quicktube.core.common.Urls.youtubeVideoId(pageUrl) ?: Regex("""/vi(?:_webp)?/([\w-]{11})/""").find(u)?.groupValues?.get(1)
    return if ("ytimg.com" in u && id != null) "https://i.ytimg.com/vi/$id/hq720.jpg" else u
}

/** Speed dial: three rows of tiles, the cover flush with the tile's left edge, led by Shuffle play. */
@Composable
private fun SpeedDial(songs: List<MediaItem>, onPlay: (Int) -> Unit, onShuffle: () -> Unit) {
    BoxWithConstraints {
        val tile = minOf(maxWidth * 0.46f, 260.dp)
        androidx.compose.foundation.lazy.grid.LazyHorizontalGrid(
            androidx.compose.foundation.lazy.grid.GridCells.Fixed(3),
            Modifier.fillMaxWidth().height(64.dp * 3 + 8.dp * 2),
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "shuffle") {
                Row(
                    Modifier.width(tile).height(64.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .pressable(scaleDown = 0.97f, onClick = onShuffle).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(QtIcons.Shuffle, null, modifier = Modifier.size(24.dp))
                    Text("Shuffle play", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 14.dp))
                }
            }
            items(songs.size, key = { songs[it].url }) { i ->
                val s = songs[i]
                Row(
                    Modifier.width(tile).height(64.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainer)
                        .pressable(scaleDown = 0.97f, role = null, onClick = { onPlay(i) }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Thumbnail(s.thumbnail, Modifier.size(64.dp), corner = 0)
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(s.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.uploader, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** Material 3 Expressive's nine-scallop "cookie": a circle whose edge waves gently nine times. */
private val CookieShape = androidx.compose.foundation.shape.GenericShape { size, _ ->
    val cx = size.width / 2; val cy = size.height / 2
    val r = minOf(cx, cy)
    val steps = 180
    for (i in 0..steps) {
        val t = 2 * Math.PI * i / steps
        val radius = r * (0.95 + 0.05 * kotlin.math.cos(9 * t))
        val x = (cx + radius * kotlin.math.cos(t - Math.PI / 2)).toFloat()
        val y = (cy + radius * kotlin.math.sin(t - Math.PI / 2)).toFloat()
        if (i == 0) moveTo(x, y) else lineTo(x, y)
    }
    close()
}

/** An artist in the cookie shape, name below. */
@Composable
private fun ArtistCard(item: MediaItem, onClick: () -> Unit) {
    val cookie = CookieShape
    Column(Modifier.width(112.dp).pressable(scaleDown = 0.95f, role = null, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(112.dp).clip(cookie).background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            coil3.compose.AsyncImage(item.thumbnail, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
    }
}

/** "Your playlists": the phone's song playlists (Flow's library row), with a tile to make or manage them. */
@Composable
private fun MyPlaylists(actions: HomeActions) {
    val repo = org.koin.compose.koinInject<com.edureminder.quicktube.core.data.LocalPlaylistRepository>()
    val lists by remember(repo) { repo.playlists.map { l -> l.filter { it.isMusic } } }.collectAsStateWithLifecycle(emptyList())
    Column(Modifier.padding(top = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Your playlists", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            Row(
                Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .pressable(scaleDown = 0.92f, onClick = { actions.openPlaylists(true) }).padding(horizontal = 14.dp, vertical = 6.dp),
            ) { Text("See all", style = MaterialTheme.typography.labelLarge) }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            item(key = "new") {
                Column(Modifier.width(120.dp).pressable(scaleDown = 0.95f, onClick = { actions.openPlaylists(true) })) {
                    Box(Modifier.size(120.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                        Icon(QtIcons.Add, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(36.dp))
                    }
                    Text(if (lists.isEmpty()) "New playlist" else "New / import", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                }
            }
            items(lists, key = { it.id }) { p ->
                Column(Modifier.width(120.dp).pressable(scaleDown = 0.95f, onClick = { actions.openLocalPlaylist(p.id) })) {
                    Box(Modifier.size(120.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                        val c = p.covers
                        when {
                            c.isEmpty() -> Icon(QtIcons.LibraryMusic, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                            c.size < 4 -> coil3.compose.AsyncImage(musicArt(c.first(), ""), null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            else -> Column(Modifier.fillMaxSize()) {
                                for (r in 0..1) Row(Modifier.weight(1f)) {
                                    for (k in 0..1) coil3.compose.AsyncImage(musicArt(c[r * 2 + k], ""), null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                        modifier = Modifier.weight(1f).fillMaxSize())
                                }
                            }
                        }
                    }
                    Text(p.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp))
                    Text(if (p.count == 1) "1 song" else "${p.count} songs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
