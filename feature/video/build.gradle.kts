plugins {
    alias(libs.plugins.quicktube.kmp.feature)
}

kotlin {
    sourceSets.commonMain.dependencies {
        implementation(projects.core.download)
    }
    sourceSets.androidMain.dependencies {
        implementation(projects.core.media)
        implementation(projects.core.playerui)
    }
}
