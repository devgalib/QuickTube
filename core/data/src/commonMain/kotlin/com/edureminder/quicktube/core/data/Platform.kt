package com.edureminder.quicktube.core.data

/** A small thread-safe least-recently-used cache (Android's LruCache isn't available on desktop). */
internal class LruCache<K, V>(private val max: Int) {
    private val map = object : LinkedHashMap<K, V>(max, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > max
    }

    @Synchronized fun get(key: K): V? = map[key]
    @Synchronized fun put(key: K, value: V) { map[key] = value }
}

/** Flags that are set once and stay set (Android SharedPreferences, desktop java.util.prefs). */
interface OneTimeFlags {
    fun isSet(name: String): Boolean
    fun set(name: String)
}

/** Our games for the home screen where there are none to show (desktop: they're Android games). */
internal class NoGamesRepository : GamesRepository {
    override val games = kotlinx.coroutines.flow.MutableStateFlow(com.edureminder.quicktube.core.model.PromoGames(games = emptyList()))
    override fun refreshIfDue() = Unit
}

/** Bindings each platform makes itself: the library (folders, media index), browser flags, gallery and games. */
internal expect val platformDataModule: org.koin.core.module.Module
