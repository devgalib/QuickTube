plugins {
    alias(libs.plugins.quicktube.kmp.library)
}

kotlin {
    sourceSets.commonMain.dependencies {
        api(projects.core.model)
    }
    sourceSets.androidMain.dependencies {
        implementation(libs.androidx.core.ktx)
    }
}
