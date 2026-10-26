plugins {
    alias(libs.plugins.quicktube.android.library.compose)
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.designsystem)
    api(projects.core.media)
    api(libs.androidx.media3.ui.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
