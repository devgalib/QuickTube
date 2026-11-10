import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * A module shared by the Android app and the desktop app: Kotlin Multiplatform with an Android target and a JVM
 * "desktop" target. Both run on the JVM, so shared code lives in `commonMain` and may use JDK APIs; Android-only code
 * goes in `androidMain`, desktop-only code in `desktopMain`.
 */
class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        pluginManager.apply("com.android.kotlin.multiplatform.library")
        val kotlin = extensions.getByType<KotlinMultiplatformExtension>()
        kotlin.apply {
            jvm("desktop")
            compilerOptions {
                freeCompilerArgs.addAll(
                    "-opt-in=kotlin.RequiresOptIn",
                    "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                    "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
                    "-Xannotation-default-target=param-property",
                    "-Xexpect-actual-classes",
                )
            }
            targets.configureEach {
                compilations.configureEach {
                    compileTaskProvider.configure {
                        compilerOptions {
                            if (this is org.jetbrains.kotlin.gradle.dsl.KotlinJvmCompilerOptions) jvmTarget.set(JvmTarget.JVM_17)
                        }
                    }
                }
            }
            sourceSets.getByName("commonMain").dependencies {
                implementation(project.dependencies.platform(libs.lib("koin-bom")))
                implementation(libs.lib("koin-core"))
                implementation(libs.lib("kotlinx-coroutines-core"))
            }
            sourceSets.getByName("androidMain").dependencies {
                implementation(libs.lib("koin-android"))
                implementation(libs.lib("kotlinx-coroutines-android"))
            }
            sourceSets.getByName("desktopMain").dependencies {
                implementation(libs.lib("kotlinx-coroutines-swing"))
            }
            sourceSets.getByName("desktopTest").dependencies {
                implementation(libs.lib("junit"))
            }
        }
        (kotlin as org.gradle.api.plugins.ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryExtension>("android") {
            namespace = "com.edureminder.quicktube" + path.replace(':', '.').replace('-', '_')
            compileSdk = QuickTubeSdk.COMPILE
            minSdk = QuickTubeSdk.MIN
            enableCoreLibraryDesugaring = true
        }
        dependencies.add("coreLibraryDesugaring", libs.lib("desugar-jdk-libs"))
    }
}
