package com.edureminder.quicktube.core.extractor

import android.content.Context
import android.telephony.TelephonyManager
import okhttp3.OkHttpClient
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Country for trending content: SIM/network country first (like YouTube does), then the device locale. */
fun detectCountry(context: Context): String {
    val tm = context.getSystemService(TelephonyManager::class.java)
    val iso = listOfNotNull(tm?.networkCountryIso, tm?.simCountryIso).firstOrNull { it.length == 2 }
    return (iso ?: Locale.getDefault().country).uppercase().ifBlank { "US" }
}

val extractorModule = module {
    single {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
    single { NewPipeMediaExtractor(get(), Locale.getDefault().language.ifBlank { "en" }, detectCountry(androidContext())) }
    single<MediaExtractor> { get<NewPipeMediaExtractor>() }
    single { WebMediaFinder(get()) }
    single { HlsClient(get()) }
    single { StreamResolver(get(), get()) }
}
