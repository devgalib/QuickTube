package com.edureminder.quicktube.core.update

import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module

/** Provide the GitHub "latest release" API URL as `named("updateUrl")` from the app module. */
val updateModule = module {
    single { UpdateManager(androidContext(), get(), get(named("updateUrl"))) }
}
