package com.edureminder.quicktube.core.data

import android.content.Context
import android.media.MediaScannerConnection
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.io.File

internal actual val platformDataModule: org.koin.core.module.Module = module {
    single<LibraryRepository> {
        val context = androidContext()
        LibraryRepositoryImpl(
            get(), get(),
            partsDir = File(context.noBackupFilesDir, "parts"),
            vaultRoot = File(context.filesDir, "vault"),
            scanFiles = { paths -> MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null) },
        ) { get<SettingsRepository>().current().downloadDir }
    }
    single<BrowserRepository> {
        val prefs = androidContext().getSharedPreferences("sites", Context.MODE_PRIVATE)
        BrowserRepositoryImpl(get(), get(), get(), object : OneTimeFlags {
            override fun isSet(name: String) = prefs.getBoolean(name, false)
            override fun set(name: String) = prefs.edit().putBoolean(name, true).apply()
        })
    }
    single<GalleryRepository> { MediaStoreGalleryRepository(androidContext()) }
    single<GamesRepository> { RemoteConfigGamesRepository(androidContext()) }
}
