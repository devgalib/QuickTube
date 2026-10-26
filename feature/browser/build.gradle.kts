plugins {
    alias(libs.plugins.quicktube.android.feature)
}

dependencies {
    implementation(projects.core.download)
    implementation(projects.core.extractor)
    implementation(projects.core.playerui)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.webkit)
}
