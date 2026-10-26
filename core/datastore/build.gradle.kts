plugins {
    alias(libs.plugins.quicktube.android.library)
}

dependencies {
    api(projects.core.model)
    implementation(libs.androidx.datastore.preferences)
}
