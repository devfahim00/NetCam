import java.io.File
import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.devfahim00.netcam"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.devfahim00.netcam"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "1.2.0"

        // The bundled selfie-segmentation model ships large MediaPipe native
        // libraries per ABI. All phones released since 2019 are arm64, so we
        // keep a single lean APK for real devices (the app still installs on
        // emulators; segmentation simply falls back gracefully there).
        ndk {
            abiFilters += listOf("arm64-v8a")
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    // The depth model must stay uncompressed so it can be memory-mapped.
    androidResources {
        noCompress += "tflite"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    // CameraX
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // ML Kit Subject Segmentation (any-object portrait detection, unbundled)
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")

    // ML Kit Selfie Segmentation (bundled, offline person mask — reliable fallback)
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")

    // TensorFlow Lite — runs the MiDaS depth model that drives portrait blur.
    implementation("org.tensorflow:tensorflow-lite:2.14.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}

// ---------------------------------------------------------------------------
// MiDaS depth model (~66 MB) is downloaded once at build time instead of being
// committed to git. Works the same locally and in GitHub Actions.
// ---------------------------------------------------------------------------
val depthModelFile = layout.projectDirectory.file("src/main/assets/midas.tflite").asFile

val downloadDepthModel by tasks.registering {
    outputs.file(depthModelFile)
    doLast {
        if (!depthModelFile.exists() || depthModelFile.length() < 1_000_000L) {
            depthModelFile.parentFile.mkdirs()
            val url = URL("https://github.com/isl-org/MiDaS/releases/download/v2_1/model_opt.tflite")
            val tmp = File(depthModelFile.parentFile, "midas.tflite.part")
            url.openStream().use { input ->
                tmp.outputStream().use { out -> input.copyTo(out) }
            }
            check(tmp.length() > 1_000_000L) { "Depth model download looks incomplete" }
            tmp.renameTo(depthModelFile)
        }
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(downloadDepthModel)
}
