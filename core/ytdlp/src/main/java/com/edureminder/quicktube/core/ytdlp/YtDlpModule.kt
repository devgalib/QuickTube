package com.edureminder.quicktube.core.ytdlp

import com.edureminder.quicktube.core.extractor.UniversalExtractor
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val ytDlpModule = module {
    single { YtDlpExtractor(androidContext()) }
    single<UniversalExtractor> { get<YtDlpExtractor>() }
}
