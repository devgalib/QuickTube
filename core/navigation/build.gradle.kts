plugins {
    alias(libs.plugins.quicktube.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets.commonMain.dependencies {
        api(libs.kotlinx.serialization.json)
        implementation(projects.core.model)
        implementation(projects.core.common)
        implementation(libs.cmp.navigation.compose)
    }
}
