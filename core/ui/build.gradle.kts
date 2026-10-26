plugins {
    alias(libs.plugins.quicktube.android.library.compose)
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.designsystem)
    implementation(libs.coil.compose)
}
