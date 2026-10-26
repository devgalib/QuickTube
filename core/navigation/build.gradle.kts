plugins {
    alias(libs.plugins.quicktube.android.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.kotlinx.serialization.json)
    implementation(libs.androidx.navigation.compose)
}
