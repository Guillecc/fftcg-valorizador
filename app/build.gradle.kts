plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Número de build de GitHub Actions (1 si se compila local)
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "cl.guillecc.fftcg"
    compileSdk = 35

    defaultConfig {
        applicationId = "cl.guillecc.fftcg"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        // Solo arm64 (el teléfono es un Xiaomi 14T): el modelo de ML Kit va para una sola ABI
        // y el APK pesa mucho menos. No instala en teléfonos de 32 bits ni emuladores x86.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Sin firma en Gradle: el release sale sin firmar (app-release-unsigned.apk) y lo firma el job
    // "sign" de build.yml con apksigner, para que la llave nunca esté donde corre Gradle.

    buildTypes {
        release {
            // R8: recorta código y recursos sin usar (APK más chico, Compose más fluido)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    lint {
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // Escáner: cámara + lectura del código impreso en la carta (modelo incluido en el APK, sin internet)
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
