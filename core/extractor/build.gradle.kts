plugins {
    alias(libs.plugins.quicktube.kmp.library)
}

kotlin {
    sourceSets.commonMain.dependencies {
        api(projects.core.model)
        implementation(projects.core.common)
        implementation(libs.newpipe.extractor)
        implementation(libs.okhttp)
        implementation(libs.kotlinx.serialization.json)
        // org.json is part of Android; the desktop app ships the same library.
        compileOnly(libs.org.json)
    }
    sourceSets.desktopMain.dependencies {
        implementation(libs.org.json)
    }
}
