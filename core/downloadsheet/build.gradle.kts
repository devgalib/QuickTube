plugins {
    alias(libs.plugins.quicktube.kmp.compose)
}

kotlin {
    sourceSets.commonMain.dependencies {
        implementation(projects.core.model)
        implementation(projects.core.common)
        implementation(projects.core.designsystem)
        implementation(projects.core.ui)
        implementation(projects.core.data)
        implementation(projects.core.database)
        implementation(projects.core.download)
        implementation(projects.core.analytics)
        api(projects.core.extractor)
        implementation(libs.coil.compose)
        implementation(libs.okhttp)
    }
}
