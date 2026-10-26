plugins {
    alias(libs.plugins.quicktube.android.library)
    alias(libs.plugins.quicktube.android.room)
}

dependencies {
    api(projects.core.model)
}
