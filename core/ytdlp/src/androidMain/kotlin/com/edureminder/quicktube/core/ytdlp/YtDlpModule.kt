package com.edureminder.quicktube.core.ytdlp

import com.edureminder.quicktube.core.extractor.BilibiliReader
import com.edureminder.quicktube.core.extractor.ChainExtractor
import com.edureminder.quicktube.core.extractor.DailymotionReader
import com.edureminder.quicktube.core.extractor.VimeoReader
import com.edureminder.quicktube.core.extractor.FacebookReader
import com.edureminder.quicktube.core.extractor.UniversalExtractor
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

actual val ytDlpModule: org.koin.core.module.Module = module {
    single { YtDlpExtractor(androidContext()) }
    // Fast native readers first (Facebook's full quality ladder, Dailymotion's HLS, Bilibili's streams, each in ~1 s),
    // yt-dlp's 1800+ sites after.
    single<UniversalExtractor> { ChainExtractor(listOf(FacebookReader(get()), DailymotionReader(get(), get()), VimeoReader(get(), get()), BilibiliReader(get()), get<YtDlpExtractor>())) }
}
