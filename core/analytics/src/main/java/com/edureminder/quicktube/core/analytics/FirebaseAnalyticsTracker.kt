package com.edureminder.quicktube.core.analytics

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/** Firebase Analytics + Crashlytics. Collection is off in debug builds and when the user opts out. */
internal class FirebaseAnalyticsTracker(context: Context) : Analytics {
    private val analytics = FirebaseAnalytics.getInstance(context)
    private val crashlytics = FirebaseCrashlytics.getInstance()
    private val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    @Volatile private var enabled = !debuggable

    init { apply() }

    private fun apply() {
        analytics.setAnalyticsCollectionEnabled(enabled)
        crashlytics.isCrashlyticsCollectionEnabled = enabled
    }

    override fun setEnabled(enabled: Boolean) {
        this.enabled = enabled && !debuggable
        apply()
    }

    override fun screen(name: String) {
        if (!enabled) return
        analytics.logEvent(FirebaseAnalytics.Event.SCREEN_VIEW, Bundle().apply {
            putString(FirebaseAnalytics.Param.SCREEN_NAME, name)
            putString(FirebaseAnalytics.Param.SCREEN_CLASS, name)
        })
    }

    override fun event(name: String, params: Map<String, Any?>) {
        if (!enabled) return
        analytics.logEvent(name, Bundle().apply {
            params.forEach { (k, v) ->
                when (v) {
                    is Long -> putLong(k, v); is Int -> putLong(k, v.toLong()); is Double -> putDouble(k, v)
                    is Boolean -> putString(k, v.toString()); null -> Unit; else -> putString(k, v.toString().take(100))
                }
            }
        })
    }

    override fun recordError(t: Throwable) {
        if (enabled) crashlytics.recordException(t)
    }
}

val analyticsModule = module {
    single<Analytics> {
        val ctx = androidContext()
        // google-services.json is not in the public repo; without it Firebase isn't initialised and we stay silent.
        if (FirebaseApp.getApps(ctx).isNotEmpty() || runCatching { FirebaseApp.initializeApp(ctx) }.getOrNull() != null) {
            FirebaseAnalyticsTracker(ctx)
        } else NoOpAnalytics
    }
}
