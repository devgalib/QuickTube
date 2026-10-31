plugins {
    alias(libs.plugins.quicktube.android.library)
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.data)
    api(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.exoplayer.hls)
    api(libs.androidx.media3.session)
    // Software audio decoders for codecs phones often lack (AC-3, E-AC-3, DTS, TrueHD, WMA); used only as a fallback.
    implementation(libs.media3.ffmpeg.decoder)
    implementation(libs.androidx.core.ktx)
}
