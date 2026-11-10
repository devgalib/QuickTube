package com.edureminder.quicktube.core.analytics

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

/** The phone's country as a two-letter code ("BD", "IN"): the mobile network's, then the SIM's, then the phone's region. */
object DeviceCountry {
    @Volatile private var cached: String? = null

    fun code(context: Context): String = cached ?: run {
        val tm = context.getSystemService(TelephonyManager::class.java)
        val code = listOfNotNull(
            runCatching { tm?.networkCountryIso }.getOrNull(), runCatching { tm?.simCountryIso }.getOrNull(), Locale.getDefault().country,
        ).firstOrNull { it.length == 2 }?.uppercase() ?: "ZZ"
        code.also { cached = it }
    }
}
