plugins {
    `kotlin-dsl`
}

group = "com.edureminder.quicktube.buildlogic"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

dependencies {
    compileOnly(libs.android.gradle.plugin)
    compileOnly(libs.kotlin.gradle.plugin)
    compileOnly(libs.compose.gradle.plugin)
    compileOnly(libs.ksp.gradle.plugin)
    compileOnly(libs.compose.multiplatform.gradle.plugin)
}

gradlePlugin {
    plugins {
        register("androidApplication") {
            id = "quicktube.android.application"
            implementationClass = "AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "quicktube.android.library"
            implementationClass = "AndroidLibraryConventionPlugin"
        }
        register("androidLibraryCompose") {
            id = "quicktube.android.library.compose"
            implementationClass = "AndroidLibraryComposeConventionPlugin"
        }
        register("androidFeature") {
            id = "quicktube.android.feature"
            implementationClass = "AndroidFeatureConventionPlugin"
        }
        register("kmpLibrary") {
            id = "quicktube.kmp.library"
            implementationClass = "KmpLibraryConventionPlugin"
        }
        register("kmpCompose") {
            id = "quicktube.kmp.compose"
            implementationClass = "KmpComposeConventionPlugin"
        }
        register("kmpFeature") {
            id = "quicktube.kmp.feature"
            implementationClass = "KmpFeatureConventionPlugin"
        }
        register("androidRoom") {
            id = "quicktube.android.room"
            implementationClass = "AndroidRoomConventionPlugin"
        }
    }
}
