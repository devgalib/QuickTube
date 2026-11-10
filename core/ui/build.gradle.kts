plugins {
    alias(libs.plugins.quicktube.kmp.compose)
}

kotlin {
    sourceSets.commonMain.dependencies {
        api(projects.core.model)
        implementation(projects.core.common)
        implementation(projects.core.designsystem)
        implementation(libs.coil.compose)
    }
    sourceSets.androidMain.dependencies {
        implementation(libs.androidx.activity.compose)
    }
}
