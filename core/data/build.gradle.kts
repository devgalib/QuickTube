plugins {
    alias(libs.plugins.quicktube.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android { withHostTest {} }
    sourceSets.commonMain.dependencies {
        api(projects.core.model)
        api(projects.core.common)
        implementation(projects.core.database)
        implementation(projects.core.datastore)
        implementation(projects.core.extractor)
        implementation(libs.kotlinx.serialization.json)
    }
    sourceSets.androidMain.dependencies {
        implementation(libs.androidx.core.ktx)
        // Our games on the home screen come from Remote Config (works without it: built-in list, see docs/PROMO_GAMES.md).
        implementation(project.dependencies.platform(libs.firebase.bom))
        implementation(libs.firebase.config)
    }
    sourceSets.getByName("androidHostTest").dependencies {
        implementation(libs.junit)
    }
}
