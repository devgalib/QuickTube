plugins {
    alias(libs.plugins.quicktube.android.library)
}

dependencies {
    api(projects.core.model)
    api(projects.core.common)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.core.extractor)
    implementation(libs.androidx.core.ktx)
}
