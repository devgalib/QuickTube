plugins {
    alias(libs.plugins.quicktube.kmp.library)
}

kotlin {
    sourceSets.commonMain.dependencies {
        api(projects.core.model)
        implementation(projects.core.common)
        implementation(projects.core.extractor)
        implementation(libs.kotlinx.serialization.json)
        implementation(libs.okhttp)
    }
    sourceSets.androidMain.dependencies {
        implementation(libs.youtubedl.android)
    }
}
