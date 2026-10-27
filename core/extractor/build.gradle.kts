plugins {
    alias(libs.plugins.quicktube.android.library)
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    implementation(libs.newpipe.extractor)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    // Android's org.json is a stub on the JVM; use the real one in unit tests.
    testImplementation(libs.org.json)
}
