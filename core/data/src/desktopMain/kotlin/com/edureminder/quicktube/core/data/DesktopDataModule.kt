package com.edureminder.quicktube.core.data

import com.edureminder.quicktube.core.common.DesktopDirs
import com.edureminder.quicktube.core.model.GalleryLibrary
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.dsl.module
import java.io.File
import java.util.prefs.Preferences

internal actual val platformDataModule: org.koin.core.module.Module = module {
    single<LibraryRepository> {
        LibraryRepositoryImpl(
            get(), get(),
            partsDir = DesktopDirs.parts,
            vaultRoot = File(DesktopDirs.data, "vault"),
            scanFiles = {},
        ) { get<SettingsRepository>().current().downloadDir }
    }
    single<BrowserRepository> {
        val prefs = Preferences.userRoot().node("com/edureminder/quicktube/sites")
        BrowserRepositoryImpl(get(), get(), get(), object : OneTimeFlags {
            override fun isSet(name: String) = prefs.getBoolean(name, false)
            override fun set(name: String) = prefs.putBoolean(name, true)
        })
    }
    // The phone gallery tab isn't part of the desktop app; an empty library keeps shared screens working.
    single<GalleryRepository> {
        object : GalleryRepository {
            override val library = MutableStateFlow<GalleryLibrary?>(GalleryLibrary(emptyList(), complete = true))
            override fun load(force: Boolean) = Unit
            override suspend fun mediaIdFor(path: String): Long? = null
        }
    }
    single<GamesRepository> { NoGamesRepository() }
}
