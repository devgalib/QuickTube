plugins {
    alias(libs.plugins.quicktube.kmp.library)
}

kotlin {
    sourceSets.commonMain.dependencies {
        api(projects.core.model)
        implementation(projects.core.common)
        api(libs.androidx.datastore.preferences.core)
    }
    sourceSets.androidMain.dependencies {
        implementation(libs.androidx.datastore.preferences)
    }
}
