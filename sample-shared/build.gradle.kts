import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

// The seed never reaches the repository: it is read from local.properties at build time and
// baked into a generated object under build/.
val generateSampleConfig = tasks.register<GenerateSampleConfig>("generateSampleConfig") {
    localProperties.from(rootProject.layout.projectDirectory.file("local.properties"))
    outputDirectory.set(layout.buildDirectory.dir("generated/sample"))
}

kotlin {
    androidTarget {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(generateSampleConfig)
            dependencies {
                implementation(libs.compose.multiplatform.runtime)
                implementation(libs.compose.multiplatform.foundation)
                implementation(libs.compose.multiplatform.material3)
                implementation(libs.compose.multiplatform.ui.tooling.preview)
                implementation(libs.androidx.lifecycle.runtime.compose)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        // The kit is JVM code shared by the two JVM-backed targets only, so everything that
        // touches it lives here instead of commonMain.
        val jvmCommonMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                api(project(":monerokit"))
            }
        }
        androidMain { dependsOn(jvmCommonMain) }
        val desktopMain by getting { dependsOn(jvmCommonMain) }
    }
}

android {
    namespace = "com.piratecash.monero.sample"
    compileSdk = 36
    defaultConfig { minSdk = 27 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

@CacheableTask
abstract class GenerateSampleConfig : DefaultTask() {

    // A file collection, not an InputFile: local.properties is absent on a fresh checkout and an
    // absent InputFile fails validation instead of generating an empty config.
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val localProperties: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val properties = Properties()
        localProperties.files.filter { it.isFile }.forEach { file ->
            file.inputStream().use { properties.load(it) }
        }
        fun value(key: String) = properties.getProperty(key).orEmpty().trim().removeSurrounding("\"")
            .replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")

        val directory = outputDirectory.get().asFile.resolve("com/piratecash/monero/sample")
        directory.mkdirs()
        directory.resolve("SampleConfig.kt").writeText(
            """
            package com.piratecash.monero.sample

            internal object SampleConfig {
                const val WORDS: String = "${value("words")}"
                const val RESTORE_HEIGHT: String = "${value("restore_height")}"
                const val NODE: String = "${value("node")}"
            }

            """.trimIndent()
        )
    }
}
