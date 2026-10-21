import org.gradle.api.tasks.Exec

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.deepfaceswap.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.deepfaceswap.android"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // The `ort` crate ships a prebuilt ONNX Runtime only for 64-bit ARM
        // Android, so we target arm64-v8a exclusively.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Prebuilt native libraries produced by cargo-ndk land in src/main/jniLibs.
    sourceSets["main"].jniLibs.srcDirs("src/main/jniLibs")
}

dependencies {
    // Intentionally dependency-free: the UI uses only framework classes so the
    // APK stays small and the build needs no AndroidX resolution.
}

// ---------------------------------------------------------------------------
// Rust cross-compilation
//
// Runs `cargo ndk ... build --release` and drops libdeep_faceswap_android.so
// into src/main/jniLibs/arm64-v8a. Skip with `-PskipRust=true` if you have
// already built the native library (e.g. in CI, before invoking Gradle).
// ---------------------------------------------------------------------------
val rustProjectDir = rootProject.file("../rust")
val jniLibsDir = file("src/main/jniLibs")

val cargoNdkBuild by tasks.registering(Exec::class) {
    group = "rust"
    description = "Cross-compile the Rust core for Android (arm64-v8a) via cargo-ndk."
    workingDir = rustProjectDir
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-o", jniLibsDir.absolutePath,
        "build", "--release", "-p", "deep_faceswap_android",
    )
}

if (project.findProperty("skipRust") != "true") {
    tasks.named("preBuild") { dependsOn(cargoNdkBuild) }
}
