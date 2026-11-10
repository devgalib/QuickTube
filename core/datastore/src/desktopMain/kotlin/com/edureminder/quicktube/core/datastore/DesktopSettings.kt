package com.edureminder.quicktube.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.edureminder.quicktube.core.common.DesktopDirs
import okio.Path.Companion.toOkioPath
import java.io.File

internal actual fun createSettingsDataSource(scope: org.koin.core.scope.Scope): SettingsDataSource {
    val store = PreferenceDataStoreFactory.createWithPath(produceFile = { File(DesktopDirs.data, "settings.preferences_pb").toOkioPath() })
    return SettingsDataSource(store, DesktopDirs.downloads.absolutePath)
}
