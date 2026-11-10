package com.edureminder.quicktube.core.extractor

import okhttp3.OkHttpClient
import org.koin.dsl.module
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Country for trending content: Android asks the SIM/network first (like YouTube does), desktop uses the locale. */
internal expect fun deviceCountry(scope: org.koin.core.scope.Scope): String

val extractorModule = module {
    single {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
    single { NewPipeMediaExtractor(get(), Locale.getDefault().language.ifBlank { "en" }, deviceCountry(this)) }
    single<MediaExtractor> { get<NewPipeMediaExtractor>() }
    single { WebMediaFinder(get()) }
    single { SponsorBlockClient(get()) }
    single { HlsClient(get()) }
    single { StreamResolver(get(), get()) }
}
