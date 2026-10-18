import java.time.Duration
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.multiplatform)
    `maven-publish`
}

// JitPack injects the requested tag via JITPACK_VERSION/VERSION; local builds fall back to the
// current git short SHA.
val resolvedVersion: String = providers.environmentVariable("JITPACK_VERSION")
    .orElse(providers.environmentVariable("VERSION"))
    .orElse(providers.environmentVariable("VERSION_NAME"))
    .orElse(provider {
        val process = ProcessBuilder("git", "rev-parse", "--short=7", "HEAD")
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().readLine()?.trim() ?: "unknown"
    })
    .get()

group = "com.github.piratecash.monero-kit-android"
version = resolvedVersion

val desktopPrebuilt: File = rootDir.resolve("natives/desktop/prebuilt")
val desktopNativeLibraries = mapOf(
    "linux-x64" to "libmonerujo.so",
    "macos-arm64" to "libmonerujo.dylib",
    "windows-x64" to "monerujo.dll",
)
fun desktopNativesHint(target: String = "<target>") =
    "run natives/desktop/build.sh $target or download the tag's release assets into natives/desktop/prebuilt/"

fun hostDesktopTarget(): String {
    val os = System.getProperty("os.name").lowercase()
    val family = when {
        os.startsWith("mac") -> "macos"
        os.startsWith("windows") -> "windows"
        else -> os
    }
    val arch = when (val name = System.getProperty("os.arch").lowercase()) {
        "amd64", "x86_64" -> "x64"
        "aarch64", "arm64" -> "arm64"
        else -> name
    }
    return "$family-$arch"
}

// natives/desktop/lib.sh is the one definition of the fingerprint build.sh writes into SOURCE.sha256.
fun checkoutSourceHash(): String {
    val process = ProcessBuilder("bash", "-c", "set -euo pipefail; source natives/desktop/lib.sh; source_hash .")
        .directory(rootDir)
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start()
    val hash = process.inputStream.bufferedReader().readText().trim()
    if (process.waitFor() != 0 || !hash.matches(Regex("[0-9a-f]{64}"))) {
        throw GradleException("cannot fingerprint the natives sources: needs a git checkout, bash and shasum")
    }
    return hash
}

// The jar ships only the stripped libraries; symbols and attestation files stay out.
val stageDesktopNatives by tasks.registering(Sync::class) {
    from(desktopPrebuilt) {
        include(desktopNativeLibraries.map { (target, library) -> "$target/$library" })
        into("native")
    }
    into(layout.buildDirectory.dir("desktopNatives"))
}

val checkDesktopNativesComplete by tasks.registering {
    group = "verification"
    description = "Fails unless every desktop target's library is present and built from this checkout's sources"
    doLast {
        val missing = desktopNativeLibraries
            .flatMap { (target, library) -> listOf("$target/$library", "$target/SOURCE.sha256") }
            .filter { desktopPrebuilt.resolve(it).length() == 0L }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "desktop natives are incomplete, missing or empty in natives/desktop/prebuilt: " +
                    "${missing.joinToString()}; ${desktopNativesHint()}"
            )
        }
        val checkout = checkoutSourceHash()
        val stale = desktopNativeLibraries.keys
            .associateWith { desktopPrebuilt.resolve("$it/SOURCE.sha256").readText().trim() }
            .filterValues { it != checkout }
        if (stale.isNotEmpty()) {
            throw GradleException(
                "desktop natives were built from other sources than this checkout ($checkout): " +
                    stale.entries.joinToString { (target, hash) -> "$target $hash" } +
                    "; rebuild them with natives/desktop/build.sh <target> and release them under a new tag"
            )
        }
    }
}

tasks.withType<AbstractPublishToMaven>().configureEach {
    dependsOn(checkDesktopNativesComplete)
}

kotlin {
    androidTarget {
        publishLibraryVariants("release")
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm("desktop") {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
        // Tests that load libmonerujo: kept out of desktopTest, which must run without natives.
        val main = compilations.getByName("main")
        compilations.create("nativeTest") {
            associateWith(main)
            defaultSourceSet.dependencies {
                implementation(libs.junit)
            }
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        // Not commonMain: the kit is JVM code shared by the two JVM-backed targets only.
        val jvmCommonMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                // api (not implementation): OkHttpClient and EventListener.Factory are exposed in the
                // public signature of MoneroHttpClient.
                api(libs.okhttp3)
                implementation(libs.okhttp.digest)
                implementation(libs.guava)
                implementation(libs.kermit)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.androidx.annotation)
            }
        }
        androidMain {
            // Sources come from AGP's own `main` source set; adding them here too would
            // list the same file in two fragments.
            dependsOn(jvmCommonMain)
            dependencies {
                implementation(libs.androidx.core.ktx)
                implementation(libs.androidx.appcompat)
                implementation(libs.netcipher)
            }
        }
        val desktopMain by getting {
            kotlin.srcDir("src/main/java")
            resources.srcDir(stageDesktopNatives)
            dependsOn(jvmCommonMain)
            dependencies {
                // Built into the Android platform, a separate artifact on the desktop JVM.
                implementation(libs.json)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.junit)
            }
        }
        val androidUnitTest by getting {
            dependencies {
                implementation(libs.junit)
                implementation(libs.mockk)
                implementation(libs.okhttp.mockwebserver)
                // Real org.json implementation: the Android SDK stub used by default in JVM
                // unit tests throws on every call.
                implementation(libs.json)
            }
        }
        val androidInstrumentedTest by getting {
            dependencies {
                implementation(libs.androidx.junit)
                implementation(libs.androidx.espresso.core)
            }
        }
    }
}

val desktopNativeTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Runs the desktop tests that load libmonerujo; needs the host's library in natives/desktop/prebuilt"
    val compilation = kotlin.targets.getByName<KotlinJvmTarget>("desktop").compilations.getByName("nativeTest")
    testClassesDirs = compilation.output.classesDirs
    classpath = files(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    // A hung native call fails the task instead of wedging the build.
    timeout.set(Duration.ofMinutes(20))
    testLogging {
        events("failed")
        exceptionFormat = TestExceptionFormat.FULL
    }
    val host = hostDesktopTarget()
    val hostLibrary = desktopNativeLibraries[host]?.let { desktopPrebuilt.resolve("$host/$it") }
    doFirst {
        if (hostLibrary == null) {
            throw GradleException("desktop natives exist for ${desktopNativeLibraries.keys.joinToString()}, not for $host")
        }
        if (hostLibrary.length() == 0L) {
            throw GradleException(
                "no desktop natives for this host: ${hostLibrary.relativeTo(rootDir)} is missing or empty; " +
                    desktopNativesHint(host)
            )
        }
    }
}

// Binary parity with the pre-KMP artifact: Kotlin interface defaults stay in DefaultImpls.
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmDefault.set(JvmDefaultMode.DISABLE)
}

// The Java sources sit in the pre-KMP layout, which only AGP's `main` source set knows about.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    sourceSets["desktopMain"].java.srcDir("src/main/java")
}

// Same R8/ProGuard rules for both targets: the AAR via consumerProguardFiles, the jar via META-INF.
tasks.named<Jar>("desktopJar") {
    from("consumer-rules.pro") {
        into("META-INF/proguard")
        rename { "monerokit.pro" }
    }
}

android {
    namespace = "com.piratecash.monero"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 27

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++11"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    packaging {
        jniLibs {
            keepDebugSymbols += "**/*.so"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets["main"].java.srcDir("src/androidMain/java")
}
