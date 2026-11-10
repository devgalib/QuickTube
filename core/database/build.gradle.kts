plugins {
    alias(libs.plugins.quicktube.kmp.library)
    alias(libs.plugins.ksp)
}

kotlin {
    sourceSets.commonMain.dependencies {
        api(projects.core.model)
        implementation(projects.core.common)
        api(libs.androidx.room.runtime)
    }
    sourceSets.desktopMain.dependencies {
        implementation(libs.androidx.sqlite.bundled)
    }
}

dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspDesktop", libs.androidx.room.compiler)
}
