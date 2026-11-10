plugins {
    alias(libs.plugins.quicktube.kmp.compose)
}

kotlin {
    sourceSets.commonMain.dependencies {
        api(projects.core.model)
        implementation(libs.cmp.navigation.compose)
    }
    sourceSets.androidMain.dependencies {
        implementation(libs.androidx.core.ktx)
        implementation(libs.androidx.activity.compose)
    }
}

compose.resources {
    publicResClass = false
    packageOfResClass = "com.edureminder.quicktube.core.designsystem.res"
}
