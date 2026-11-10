package com.edureminder.quicktube.core.database

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import com.edureminder.quicktube.core.common.DesktopDirs
import java.io.File

internal actual fun buildDatabase(scope: org.koin.core.scope.Scope): QuickTubeDatabase =
    Room.databaseBuilder<QuickTubeDatabase>(File(DesktopDirs.data, "quicktube.db").absolutePath)
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(Dispatchers.IO)
        .fallbackToDestructiveMigrationOnDowngrade(true)
        .build()
