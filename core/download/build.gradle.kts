plugins {
    alias(libs.plugins.quicktube.android.library)
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.data)
    implementation(projects.core.analytics)
    implementation(projects.core.database)
    implementation(projects.core.extractor)
    implementation(projects.core.datastore)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.okhttp)
    implementation(libs.androidx.media3.extractor)
    implementation(libs.androidx.media3.muxer)
}
