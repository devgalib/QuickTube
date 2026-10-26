package com.edureminder.quicktube.di

import com.edureminder.quicktube.navigation.UrlRouter
import org.koin.dsl.module

val appModule = module {
    single { UrlRouter(get(), get()) }
}
