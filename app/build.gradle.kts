import java.util.Properties

plugins {
    alias(libs.plugins.quicktube.android.application)
    alias(libs.plugins.kotlin.serialization)
}

// Firebase (Analytics + Crashlytics) is enabled only when google-services.json is present.
// It is git-ignored: maintainers' release builds get it from a CI secret; forks build without it.
if (file("google-services.json").exists()) {
    pluginManager.apply(libs.plugins.google.services.get().pluginId)
    pluginManager.apply(libs.plugins.firebase.crashlytics.get().pluginId)
}

// Release signing: CI passes RELEASE_* as environment variables; locally they come from local.properties.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use(::load)
}
fun secret(name: String): String? = System.getenv(name) ?: localProps.getProperty(name)

android {
    namespace = "com.edureminder.quicktube"

    signingConfigs {
        val storePath = secret("RELEASE_KEYSTORE_PATH")
        if (storePath != null && file(storePath).exists()) {
            create("release") {
                storeFile = file(storePath)
                storePassword = secret("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = secret("RELEASE_KEY_ALIAS")
                keyPassword = secret("RELEASE_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.edureminder.quicktube"
        // CI sets these from the release run (see .github/workflows/release.yml); local builds are "dev".
        versionCode = (project.findProperty("appVersionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("appVersionName") as String?) ?: "${project.property("appVersionBase")}.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Where the app looks for new versions. Override with -PupdateApiUrl=… to test against a local fake release.
        val updateUrl = (project.findProperty("updateApiUrl") as String?)
            ?: "https://api.github.com/repos/devgalib/QuickTube/releases/latest"
        buildConfigField("String", "UPDATE_API_URL", "\"$updateUrl\"")
        buildConfigField("boolean", "UPDATE_URL_OVERRIDDEN", "${project.hasProperty("updateApiUrl")}")
        // Phones only (the bundled Python is per-ABI); keeps the APK small.
        ndk { abiFilters += setOf("arm64-v8a", "armeabi-v7a") }
    }

    buildTypes {
        release {
            // R8: shrink + optimise (much faster cold start and smaller APK).
            optimization {
                enable = true
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        // Release code signed with the debug key, for testing real performance on a device.
        create("qa") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            applicationIdSuffix = ".qa"
        }
    }
    buildFeatures { buildConfig = true }

    // One APK per CPU type (each ~35% smaller: the bundled Python runtime is per-ABI), plus a universal one.
    // The in-app updater downloads the one matching the phone; the universal APK stays for manual installs.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
        // yt-dlp's Python runtime is unpacked from native libs at first launch.
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(projects.core.designsystem)
    implementation(projects.core.ui)
    implementation(projects.core.navigation)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.core.extractor)
    implementation(projects.core.data)
    implementation(projects.core.download)
    implementation(projects.core.media)
    implementation(projects.core.downloadsheet)
    implementation(projects.core.ytdlp)
    implementation(projects.core.analytics)
    implementation(projects.core.update)

    implementation(projects.feature.home)
    implementation(projects.feature.search)
    implementation(projects.feature.video)
    implementation(projects.feature.library)
    implementation(projects.feature.player)
    implementation(projects.feature.browser)
    implementation(projects.feature.settings)
    implementation(projects.feature.tools)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.video)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(projects.core.extractor)
    androidTestImplementation(libs.okhttp)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
