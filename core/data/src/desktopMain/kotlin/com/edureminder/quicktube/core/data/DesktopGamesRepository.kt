package com.edureminder.quicktube.core.data

import com.edureminder.quicktube.core.common.QtLog
import com.edureminder.quicktube.core.model.PromoGames
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Locale
import java.util.Properties
import java.util.UUID
import java.util.prefs.Preferences

/**
 * "Our games" on desktop: the same Remote Config document as the Android app, read over Firebase's REST API with the
 * project's settings (`firebase.properties`, made at build time from app/google-services.json when it's there). Like
 * Android, the last list is kept and shown at once, refreshed at most once per `qt_games_refresh_hours`, and rotated on
 * every app start. Builds without Firebase settings show [PromoGamesConfig.BUILT_IN].
 */
internal class DesktopGamesRepository(private val client: OkHttpClient) : GamesRepository {
    private val prefs = Preferences.userRoot().node("com/edureminder/quicktube/promo_games")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val visit: Int = prefs.getInt(VISITS, 0).also { prefs.putInt(VISITS, it + 1) }
    private val state = MutableStateFlow(cached())
    override val games: StateFlow<PromoGames> = state.asStateFlow()
    @Volatile private var fetching = false

    private val firebase: Properties? by lazy {
        javaClass.classLoader.getResourceAsStream("firebase.properties")?.use { Properties().apply { load(it) } }
            ?.takeIf { !it.getProperty("apiKey").isNullOrBlank() }
    }

    override fun refreshIfDue() {
        val hours = prefs.getFloat(REFRESH_HOURS, PromoGamesConfig.DEFAULT_REFRESH_HOURS.toFloat()).coerceAtLeast(0f)
        val age = System.currentTimeMillis() - prefs.getLong(FETCHED_AT, 0)
        if (fetching || (prefs.get(GAMES_JSON, null) != null && age in 0 until (hours * 3_600_000f).toLong())) return
        val fb = firebase ?: return
        fetching = true
        scope.launch {
            try {
                val entries = fetch(fb) ?: return@launch
                val raw = entries[PromoGamesConfig.KEY_GAMES].orEmpty()
                // An empty or broken value keeps the last good list.
                if (PromoGamesConfig.parse(raw) == null) return@launch
                prefs.put(GAMES_JSON, raw)
                prefs.put(TITLE, entries[PromoGamesConfig.KEY_TITLE]?.ifBlank { null } ?: PromoGamesConfig.DEFAULT_TITLE)
                prefs.putBoolean(ENABLED, entries[PromoGamesConfig.KEY_ENABLED]?.toBooleanStrictOrNull() ?: true)
                prefs.putFloat(REFRESH_HOURS, entries[PromoGamesConfig.KEY_REFRESH_HOURS]?.toFloatOrNull()?.takeIf { it > 0f } ?: PromoGamesConfig.DEFAULT_REFRESH_HOURS.toFloat())
                prefs.putLong(FETCHED_AT, System.currentTimeMillis())
                state.value = cached()
            } finally {
                fetching = false
            }
        }
    }

    /** Remote Config's values for this project, or null when the fetch failed (offline, no settings). */
    private fun fetch(fb: Properties): Map<String, String>? = runCatching {
        val installation = prefs.get(INSTANCE, null) ?: UUID.randomUUID().toString().replace("-", "").also { prefs.put(INSTANCE, it) }
        val body = JsonObject(
            mapOf(
                "appInstanceId" to JsonPrimitive(installation), "appId" to JsonPrimitive(fb.getProperty("appId")),
                "languageCode" to JsonPrimitive(Locale.getDefault().toLanguageTag()), "countryCode" to JsonPrimitive(Locale.getDefault().country),
                "sdkVersion" to JsonPrimitive("22.0.0"),
            ),
        ).toString()
        val url = "https://firebaseremoteconfig.googleapis.com/v1/projects/${fb.getProperty("projectNumber")}/namespaces/firebase:fetch?key=${fb.getProperty("apiKey")}"
        client.newCall(Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType())).build()).execute().use { r ->
            if (!r.isSuccessful) { QtLog.w(TAG, "Games fetch failed: HTTP ${r.code}"); return null }
            val entries = Json.parseToJsonElement(r.body.string()).jsonObject["entries"]?.jsonObject ?: return emptyMap()
            entries.mapValues { it.value.jsonPrimitive.content }
        }
    }.onFailure { QtLog.w(TAG, "Games fetch failed", it) }.getOrNull()

    /** The saved list (or the built-in one), rotated for this visit. */
    private fun cached(): PromoGames {
        if (!prefs.getBoolean(ENABLED, true)) return PromoGames(games = emptyList())
        val list = prefs.get(GAMES_JSON, null)?.let(PromoGamesConfig::parse) ?: PromoGamesConfig.BUILT_IN
        val title = prefs.get(TITLE, null) ?: PromoGamesConfig.DEFAULT_TITLE
        if (list.isEmpty()) return PromoGames(title, emptyList())
        val shift = visit % list.size
        return PromoGames(title, list.drop(shift) + list.take(shift))
    }

    private companion object {
        const val TAG = "PromoGames"
        const val GAMES_JSON = "games_json"
        const val TITLE = "title"
        const val ENABLED = "enabled"
        const val REFRESH_HOURS = "refresh_hours"
        const val FETCHED_AT = "fetched_at"
        const val VISITS = "visits"
        const val INSTANCE = "instance"
    }
}
