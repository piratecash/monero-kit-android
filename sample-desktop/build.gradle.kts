// The Kotlin plugin is already on the build classpath, so no version is requested here.
plugins {
    id("org.jetbrains.kotlin.jvm")
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

dependencies {
    implementation(project(":sample-shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.multiplatform.material3)
    // Supplies Dispatchers.Main, which Compose Desktop needs on the JVM.
    implementation(libs.kotlinx.coroutines.swing)
}

compose.desktop {
    application {
        mainClass = "com.piratecash.monero.sample.desktop.MainKt"
    }
}
