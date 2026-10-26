plugins {
    alias(libs.plugins.quicktube.android.library.compose)
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.designsystem)
    implementation(libs.okhttp)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.koin.androidx.compose)
    testImplementation(libs.junit)
    // Android's org.json is a stub on the JVM; use the real one in unit tests.
    testImplementation(libs.org.json)
}
