plugins {
    alias(libs.plugins.quicktube.android.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(projects.core.model)
    api(projects.core.common)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.core.extractor)
    implementation(libs.androidx.core.ktx)
    // Our games on the home screen come from Remote Config (works without it: built-in list, see docs/PROMO_GAMES.md).
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.config)
    implementation(libs.kotlinx.serialization.json)
}
