import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

object QuickTubeSdk {
    const val COMPILE = 37
    const val MIN = 24
    const val TARGET = 37
}

/** Shared Android + Kotlin setup for every module (app and libraries). */
internal fun Project.configureKotlinAndroid(common: CommonExtension) {
    common.apply {
        compileSdk = QuickTubeSdk.COMPILE
        defaultConfig.minSdk = QuickTubeSdk.MIN
        compileOptions.sourceCompatibility = JavaVersion.VERSION_17
        compileOptions.targetCompatibility = JavaVersion.VERSION_17
        compileOptions.isCoreLibraryDesugaringEnabled = true
    }
    extensions.configure<KotlinAndroidProjectExtension> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-opt-in=kotlin.RequiresOptIn",
                "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
                "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
                "-Xannotation-default-target=param-property",
            )
        }
    }
    dependencies {
        add("coreLibraryDesugaring", libs.lib("desugar-jdk-libs"))
    }
}

internal fun Project.configureCompose(common: CommonExtension) {
    pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
    common.buildFeatures.compose = true
    dependencies {
        val bom = libs.lib("androidx-compose-bom")
        add("implementation", platform(bom))
        add("implementation", libs.lib("androidx-compose-ui"))
        add("implementation", libs.lib("androidx-compose-ui-graphics"))
        add("implementation", libs.lib("androidx-compose-foundation"))
        add("implementation", libs.lib("androidx-compose-material3"))
        add("implementation", libs.lib("androidx-compose-ui-tooling-preview"))
        add("debugImplementation", libs.lib("androidx-compose-ui-tooling"))
    }
}
