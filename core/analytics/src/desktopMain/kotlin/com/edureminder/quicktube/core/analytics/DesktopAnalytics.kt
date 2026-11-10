package com.edureminder.quicktube.core.analytics

import org.koin.dsl.module

/** The desktop app sends no analytics. */
actual val analyticsModule: org.koin.core.module.Module = module { single<Analytics> { NoOpAnalytics } }
