import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/** A shared feature module: Compose UI + ViewModels + navigation, depending only on core modules. */
class KmpFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project): Unit = with(target) {
        pluginManager.apply("quicktube.kmp.compose")
        pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
        extensions.getByType<KotlinMultiplatformExtension>().sourceSets.getByName("commonMain").dependencies {
            implementation(project(":core:model"))
            implementation(project(":core:common"))
            implementation(project(":core:designsystem"))
            implementation(project(":core:ui"))
            implementation(project(":core:data"))
            implementation(project(":core:navigation"))
            implementation(project(":core:downloadsheet"))
            implementation(libs.lib("cmp-navigation-compose"))
            implementation(libs.lib("kotlinx-serialization-json"))
            implementation(libs.lib("coil-compose"))
        }
    }
}
