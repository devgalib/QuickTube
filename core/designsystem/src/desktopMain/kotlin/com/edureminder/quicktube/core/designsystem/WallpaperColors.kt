package com.edureminder.quicktube.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

@Composable
internal actual fun wallpaperColorScheme(dark: Boolean): ColorScheme? = null

actual val supportsWallpaperColors: Boolean get() = false
