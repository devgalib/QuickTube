package com.edureminder.quicktube.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import com.edureminder.quicktube.core.designsystem.QuickTubeTheme
import com.edureminder.quicktube.core.model.AppSettings
import com.edureminder.quicktube.core.model.ThemeMode

@Composable
fun AppSettings.isDark(): Boolean = when (themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** The app theme as configured in Settings → Appearance. */
@Composable
fun AppTheme(settings: AppSettings, content: @Composable () -> Unit) {
    QuickTubeTheme(
        darkTheme = settings.isDark(),
        accent = settings.accent,
        amoled = settings.amoled,
        dynamicColor = settings.dynamicColor,
        content = content,
    )
}
