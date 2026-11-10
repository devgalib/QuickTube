plugins {
    alias(libs.plugins.quicktube.kmp.feature)
}

kotlin {
    sourceSets.commonMain.dependencies {
        implementation(projects.core.download)
    }
}
