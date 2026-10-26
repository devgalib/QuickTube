package com.edureminder.quicktube.di

import com.edureminder.quicktube.navigation.UrlRouter
import com.edureminder.quicktube.BuildConfig
import org.koin.core.qualifier.named
import org.koin.dsl.module

val appModule = module {
    single(named("updateUrl")) { BuildConfig.UPDATE_API_URL }
    single { UrlRouter(get(), get()) }
}
