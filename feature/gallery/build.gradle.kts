plugins {
    alias(libs.plugins.quicktube.android.feature)
}

dependencies {
    implementation(projects.core.media)
    implementation(projects.core.playerui)
    // Media3 extractors, to grab a frame from videos MediaStore can't thumbnail.
    implementation(libs.androidx.media3.inspector)
    // MediaMerger, for "Video to MP3".
    implementation(projects.core.download)
}
