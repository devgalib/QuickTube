package com.edureminder.quicktube.core.model

/** One of our own games, promoted on the home screen. Comes from Remote Config (`qt_games`, see docs/PROMO_GAMES.md). */
data class PromoGame(
    /** Unique per entry; the same game may appear more than once with different ids. */
    val id: String,
    val name: String,
    /** Play Store package, e.g. com.edureminder.arrow. */
    val packageName: String,
    val iconUrl: String,
    /** Wide image (feature graphic or a landscape screenshot); the icon is used when missing. */
    val bannerUrl: String? = null,
    /** One short line, e.g. "Tap arrows to clear the maze!". */
    val tagline: String = "",
    /** Genre label, e.g. "Puzzle". */
    val genre: String = "",
    /** Store page; defaults to the Play Store page of [packageName]. */
    val storeUrl: String = "https://play.google.com/store/apps/details?id=$packageName",
)

/** The home screen's games row: its [title] and the games in this visit's order. */
data class PromoGames(val title: String = "Our games", val games: List<PromoGame> = emptyList())
