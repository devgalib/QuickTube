package com.edureminder.quicktube.core.datastore

import android.content.Context
import android.os.Environment
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import org.koin.android.ext.koin.androidContext
import java.io.File

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

internal actual fun createSettingsDataSource(scope: org.koin.core.scope.Scope): SettingsDataSource = SettingsDataSource(
    scope.androidContext().dataStore,
    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "QuickTube").absolutePath,
)
