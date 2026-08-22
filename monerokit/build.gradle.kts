import org.gradle.kotlin.dsl.annotationProcessor
import org.gradle.kotlin.dsl.compileOnly

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
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
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.timber)
    implementation(libs.guava)

    // api (not implementation): okhttp3.EventListener.Factory is exposed in the public signature of
    // NetCipherHelper.setEventListenerFactory, so the type must be visible to consumers.
    api(libs.okhttp3)
    implementation(libs.okhttp.digest)
    implementation(libs.netcipher)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    // Real org.json implementation for unit tests: the Android SDK stub used by default in JVM
    // unit tests throws on every call (org.json classes are not part of android.util.* mocking).
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.piratecash"
                artifactId = "monero-kit-android"
                version = resolvedVersion
            }
        }
    }
}
