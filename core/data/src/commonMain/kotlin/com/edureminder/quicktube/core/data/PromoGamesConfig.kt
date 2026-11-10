package com.edureminder.quicktube.core.data

import com.edureminder.quicktube.core.model.PromoGame
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The "Our games" Remote Config document (`qt_games`, see docs/PROMO_GAMES.md), read the same way on every platform. */
internal object PromoGamesConfig {
    const val KEY_GAMES = "qt_games"
    const val KEY_TITLE = "qt_games_title"
    const val KEY_ENABLED = "qt_games_enabled"
    const val KEY_REFRESH_HOURS = "qt_games_refresh_hours"
    const val DEFAULT_REFRESH_HOURS = 24.0
    const val DEFAULT_TITLE = "Our games"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Null when [raw] isn't a games document; otherwise its enabled, complete entries. */
    fun parse(raw: String): List<PromoGame>? = runCatching {
        if (raw.isBlank()) return null
        json.decodeFromString<GamesDoc>(raw).games.filter { it.enabled && it.name.isNotBlank() && it.packageName.isNotBlank() }.map { g ->
            PromoGame(
                id = g.id, name = g.name, packageName = g.packageName, iconUrl = g.icon, bannerUrl = g.banner?.ifBlank { null },
                tagline = g.tagline, genre = g.genre,
                storeUrl = g.url?.ifBlank { null } ?: "https://play.google.com/store/apps/details?id=${g.packageName}",
            )
        }
    }.getOrNull()

    /** Shown before the first successful fetch (and in builds without Firebase). */
    val BUILT_IN = listOf(
        PromoGame(
            id = "arrow", name = "Arrow Puzzle: Puzzle Escape", packageName = "com.edureminder.arrow",
            iconUrl = "https://play-lh.googleusercontent.com/92-fPbbSm080ASr0slQYAkRzoZJe49BHbKsWGAWW-HaWOEK433FVlZjXNywcfg_xuUnMBZQVWsdu6VG2xlKs=s256-rw",
            bannerUrl = "https://play-lh.googleusercontent.com/wuVcRpj6ZwqsUQ-KARBPLQJ4I25kYYZua2-cYE4E1n2c7vCmNS1CPrXJUpHhnyf2FTkjKK2UJEvV51aW2QuJmZQ=w1052-h592-rw",
            tagline = "Tap arrows to clear the maze!", genre = "Puzzle",
        ),
    )
}

@Serializable
private data class GamesDoc(val games: List<GameDto> = emptyList())

@Serializable
private data class GameDto(
    val id: String,
    val name: String,
    @SerialName("package") val packageName: String,
    val icon: String,
    val banner: String? = null,
    val tagline: String = "",
    val genre: String = "",
    val url: String? = null,
    val enabled: Boolean = true,
)
