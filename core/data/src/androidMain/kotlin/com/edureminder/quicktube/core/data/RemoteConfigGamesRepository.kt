package com.edureminder.quicktube.core.data

import android.content.Context
import android.util.Log
import com.edureminder.quicktube.core.model.PromoGames
import com.google.firebase.FirebaseApp
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * [GamesRepository] on Firebase Remote Config. The last fetched list is kept in app preferences, so every visit shows it
 * at once (and offline) without fetching; a fetch happens only when the copy is older than `qt_games_refresh_hours`
 * (24 by default, itself read from Remote Config). Builds without Firebase show [BUILT_IN].
 * Keys and the JSON format: docs/PROMO_GAMES.md.
 */
internal class RemoteConfigGamesRepository(context: Context) : GamesRepository {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("promo_games", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** This visit's rotation: each app start moves the next game to the front. */
    private val visit: Int = prefs.getInt(VISITS, 0).also { prefs.edit().putInt(VISITS, it + 1).apply() }
    private val state = MutableStateFlow(cached())
    override val games: StateFlow<PromoGames> = state.asStateFlow()
    @Volatile private var fetching = false

    override fun refreshIfDue() {
        val hours = prefs.getFloat(REFRESH_HOURS, DEFAULT_REFRESH_HOURS.toFloat()).coerceAtLeast(0f)
        val age = System.currentTimeMillis() - prefs.getLong(FETCHED_AT, 0)
        if (fetching || (prefs.contains(GAMES_JSON) && age in 0 until (hours * HOUR_MS).toLong())) return
        val rc = remoteConfig() ?: return
        fetching = true
        scope.launch {
            try {
                if (!fetchAndActivate(rc)) return@launch
                val json = rc.getString(KEY_GAMES)
                // An empty or broken value keeps the last good list.
                if (parse(json) == null) return@launch
                prefs.edit()
                    .putString(GAMES_JSON, json)
                    .putString(TITLE, rc.getString(KEY_TITLE).ifBlank { DEFAULT_TITLE })
                    .putBoolean(ENABLED, rc.getBoolean(KEY_ENABLED))
                    .putFloat(REFRESH_HOURS, rc.getDouble(KEY_REFRESH_HOURS).toFloat().takeIf { it > 0f } ?: DEFAULT_REFRESH_HOURS.toFloat())
                    .putLong(FETCHED_AT, System.currentTimeMillis())
                    .apply()
                state.value = cached()
            } finally {
                fetching = false
            }
        }
    }

    /** The saved list (or the built-in one), rotated for this visit. */
    private fun cached(): PromoGames {
        if (!prefs.getBoolean(ENABLED, true)) return PromoGames(games = emptyList())
        val list = prefs.getString(GAMES_JSON, null)?.let(::parse) ?: BUILT_IN
        val title = prefs.getString(TITLE, null) ?: DEFAULT_TITLE
        if (list.isEmpty()) return PromoGames(title, emptyList())
        val shift = visit % list.size
        return PromoGames(title, list.drop(shift) + list.take(shift))
    }

    private fun remoteConfig(): FirebaseRemoteConfig? = runCatching {
        if (FirebaseApp.getApps(appContext).isEmpty() && FirebaseApp.initializeApp(appContext) == null) return null
        FirebaseRemoteConfig.getInstance().apply {
            setDefaultsAsync(
                mapOf(KEY_ENABLED to true, KEY_TITLE to DEFAULT_TITLE, KEY_REFRESH_HOURS to DEFAULT_REFRESH_HOURS, KEY_GAMES to ""),
            )
        }
    }.onFailure { Log.w(TAG, "Remote Config unavailable", it) }.getOrNull()

    private suspend fun fetchAndActivate(rc: FirebaseRemoteConfig): Boolean = suspendCancellableCoroutine { cont ->
        // Our own clock decides when to refresh (see refreshIfDue), so Remote Config mustn't throttle this fetch.
        rc.setConfigSettingsAsync(remoteConfigSettings { minimumFetchIntervalInSeconds = 0 }).addOnCompleteListener {
            rc.fetchAndActivate().addOnCompleteListener { task ->
                if (!task.isSuccessful) Log.w(TAG, "Games fetch failed", task.exception)
                if (cont.isActive) cont.resume(task.isSuccessful)
            }
        }
    }

    companion object {
        private const val TAG = "PromoGames"
        const val KEY_GAMES = "qt_games"
        const val KEY_TITLE = "qt_games_title"
        const val KEY_ENABLED = "qt_games_enabled"
        const val KEY_REFRESH_HOURS = "qt_games_refresh_hours"
        const val DEFAULT_REFRESH_HOURS = 24.0
        private const val DEFAULT_TITLE = "Our games"
        private const val HOUR_MS = 3_600_000f
        private const val GAMES_JSON = "games_json"
        private const val TITLE = "title"
        private const val ENABLED = "enabled"
        private const val REFRESH_HOURS = "refresh_hours"
        private const val FETCHED_AT = "fetched_at"
        private const val VISITS = "visits"

        internal fun parse(raw: String) = PromoGamesConfig.parse(raw)
        internal val BUILT_IN get() = PromoGamesConfig.BUILT_IN
    }
}
