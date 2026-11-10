plugins {
    alias(libs.plugins.quicktube.android.feature)
}

dependencies {
    implementation(projects.core.update)
    implementation(projects.core.playerui)
}
