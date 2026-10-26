plugins {
    alias(libs.plugins.quicktube.android.feature)
}

dependencies {
    implementation(projects.core.media)
    implementation(projects.core.playerui)
}
