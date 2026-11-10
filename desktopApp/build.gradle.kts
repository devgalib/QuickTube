import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// The desktop app (Linux, Windows, macOS): Compose Multiplatform on the JVM, sharing the core modules and the
// Home / Search / Download screens with the Android app. See docs/DESKTOP.md.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm("desktop")
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
            "-Xannotation-default-target=param-property",
        )
    }
    sourceSets {
        val desktopMain by getting {
            dependencies {
                implementation(projects.core.model)
                implementation(projects.core.common)
                implementation(projects.core.designsystem)
                implementation(projects.core.ui)
                implementation(projects.core.navigation)
                implementation(projects.core.data)
                implementation(projects.core.database)
                implementation(projects.core.datastore)
                implementation(projects.core.extractor)
                implementation(projects.core.download)
                implementation(projects.core.downloadsheet)
                implementation(projects.core.ytdlp)
                implementation(projects.core.analytics)
                implementation(projects.feature.home)
                implementation(projects.feature.search)
                implementation(projects.feature.video)

                implementation(compose.desktop.currentOs)
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(libs.cmp.material3)
                implementation(libs.cmp.navigation.compose)
                implementation(libs.cmp.lifecycle.viewmodel.compose)
                implementation(libs.cmp.lifecycle.runtime.compose)
                implementation(project.dependencies.platform(libs.koin.bom))
                implementation(libs.koin.core)
                implementation(libs.koin.compose)
                implementation(libs.koin.compose.viewmodel)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.coil.compose)
                implementation(libs.coil.network.okhttp)
                implementation(libs.okhttp)
                implementation(libs.newpipe.extractor)
                // Video and music playback through libVLC (VLC 3 on the system, or bundled on Windows).
                implementation(libs.vlcj)
            }
        }
    }
}

/**
 * The Firebase project's client settings for the desktop app ("Our games" comes from the same Remote Config as on
 * Android), taken from app/google-services.json when it's there. That file is never committed; without it the
 * build still works and the app shows its built-in games list.
 */
abstract class FirebaseDesktopConfig : DefaultTask() {
    @get:InputFiles @get:Optional abstract val googleServices: ConfigurableFileCollection
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val dir = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val file = googleServices.files.firstOrNull { it.isFile } ?: return
        @Suppress("UNCHECKED_CAST")
        val json = groovy.json.JsonSlurper().parse(file) as Map<String, Any?>
        val project = json["project_info"] as Map<String, Any?>
        val clients = json["client"] as List<Map<String, Any?>>
        val client = clients.firstOrNull {
            ((it["client_info"] as Map<String, Any?>)["android_client_info"] as Map<String, Any?>)["package_name"] == "com.edureminder.quicktube"
        } ?: clients.first()
        val appId = (client["client_info"] as Map<String, Any?>)["mobilesdk_app_id"]
        val apiKey = ((client["api_key"] as List<Map<String, Any?>>).first())["current_key"]
        dir.resolve("firebase.properties").writeText("projectNumber=${project["project_number"]}\nappId=$appId\napiKey=$apiKey\n")
    }
}

val firebaseDesktopConfig by tasks.registering(FirebaseDesktopConfig::class) {
    googleServices.from(rootProject.layout.projectDirectory.file("app/google-services.json").asFile.takeIf { it.isFile } ?: emptyList<File>())
    outputDir = layout.buildDirectory.dir("generated/firebase")
}
kotlin.sourceSets.getByName("desktopMain").resources.srcDir(firebaseDesktopConfig.map { it.outputDir })

val appVersion = (findProperty("appVersionName") as String?)?.substringBefore("-") ?: "${property("appVersionBase")}.0"

compose.desktop {
    application {
        mainClass = "com.edureminder.quicktube.desktop.MainKt"
        // Packaging needs a full JDK (jpackage, jlink); Gradle finds or downloads JDK 21 for it.
        javaHome = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }.get().metadata.installationPath.asFile.absolutePath
        jvmArgs += listOf("-Xmx1g", "-Dsun.java2d.uiScale.enabled=true")
        nativeDistributions {
            // Tools shipped inside the app (desktopApp/resources/<os>/, e.g. ffmpeg.exe on Windows, filled by CI);
            // found at run time through the "compose.application.resources.dir" property (see core:common Tools).
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi, TargetFormat.Exe, TargetFormat.Dmg)
            packageName = "QuickTube"
            packageVersion = appVersion
            description = "Download and watch videos and music from YouTube and 1800+ sites"
            vendor = "edureminder"
            copyright = "© edureminder.com"
            licenseFile.set(rootProject.file("LICENSE"))
            modules("java.sql", "java.naming", "java.prefs", "java.net.http", "jdk.unsupported", "jdk.crypto.ec", "jdk.accessibility")
            linux {
                packageName = "quicktube"
                iconFile.set(project.file("icons/quicktube.png"))
                menuGroup = "AudioVideo"
                appCategory = "Video"
                debMaintainer = "billing@edureminder.com"
                shortcut = true
            }
            windows {
                iconFile.set(project.file("icons/quicktube.ico"))
                menuGroup = "QuickTube"
                perUserInstall = true
                shortcut = true
                dirChooser = true
                // Keep this id forever: Windows uses it to upgrade an installed QuickTube in place.
                upgradeUuid = "6b0c6f0e-5c3e-4c8e-9d0b-4f3c2a9b7e11"
            }
            macOS {
                bundleID = "com.edureminder.quicktube.desktop"
                iconFile.set(project.file("icons/quicktube.png"))
            }
        }
    }
}
