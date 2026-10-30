plugins {
    alias(libs.plugins.quicktube.android.feature)
}

dependencies {
    implementation(projects.core.media)
    implementation(projects.core.playerui)
    // MediaMerger, for "Video to MP3".
    implementation(projects.core.download)
}
