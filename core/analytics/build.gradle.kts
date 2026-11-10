plugins {
    alias(libs.plugins.quicktube.kmp.library)
}

kotlin {
    sourceSets.androidMain.dependencies {
        implementation(project.dependencies.platform(libs.firebase.bom))
        implementation(libs.firebase.analytics)
        implementation(libs.firebase.crashlytics)
    }
}
