package com.edureminder.quicktube.core.extractor

import android.content.Context
import android.telephony.TelephonyManager
import org.koin.android.ext.koin.androidContext
import java.util.Locale

/** Country for trending content: SIM/network country first (like YouTube does), then the device locale. */
fun detectCountry(context: Context): String {
    val tm = context.getSystemService(TelephonyManager::class.java)
    val iso = listOfNotNull(tm?.networkCountryIso, tm?.simCountryIso).firstOrNull { it.length == 2 }
    return (iso ?: Locale.getDefault().country).uppercase().ifBlank { "US" }
}

internal actual fun deviceCountry(scope: org.koin.core.scope.Scope): String = detectCountry(scope.androidContext())
