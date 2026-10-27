package com.edureminder.quicktube.core.ytdlp

import com.edureminder.quicktube.core.extractor.ChainExtractor
import com.edureminder.quicktube.core.extractor.FacebookReader
import com.edureminder.quicktube.core.extractor.UniversalExtractor
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val ytDlpModule = module {
    single { YtDlpExtractor(androidContext()) }
    // Fast native readers first (Facebook's full quality ladder in ~1 s), yt-dlp's 1800+ sites after.
    single<UniversalExtractor> { ChainExtractor(listOf(FacebookReader(get()), get<YtDlpExtractor>())) }
}
