plugins {
    alias(libs.plugins.quicktube.kmp.library)
}

kotlin {
    android { withHostTest {} }
    sourceSets.commonMain.dependencies {
        api(projects.core.model)
        implementation(projects.core.common)
        implementation(projects.core.data)
        implementation(projects.core.analytics)
        implementation(projects.core.database)
        implementation(projects.core.extractor)
        implementation(projects.core.datastore)
        implementation(libs.kotlinx.serialization.json)
        implementation(libs.okhttp)
    }
    sourceSets.androidMain.dependencies {
        implementation(libs.androidx.core.ktx)
        implementation(libs.androidx.lifecycle.service)
        implementation(libs.androidx.media3.extractor)
        implementation(libs.androidx.media3.muxer)
        implementation(libs.jump3r)
        // Photo posts with music (TikTok, Instagram) are made into a video with their sound.
        implementation(libs.androidx.media3.transformer)
    }
    sourceSets.getByName("androidHostTest").dependencies {
        implementation(libs.junit)
    }
}
