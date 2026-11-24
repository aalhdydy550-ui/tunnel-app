plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "dev.aalhdydy550.tunnel"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "dev.aalhdydy550.tunnel"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ABIs: arm64-v8a + armeabi-v7a. The libbox AAR contains all
        // ABIs (sing-box builds every Android ABI by default), and
        // Gradle keeps only the filtered ones in the APK. armeabi-v7a
        // is required: the user's Infinix Smart 5 (X657C) runs 32-bit
        // Android 10 Go, so the arm64-only APK failed to install on
        // it (INSTALL_FAILED_NO_MATCHING_ABIS, run 66).
        ndk {
            abiFilters += "arm64-v8a"
            abiFilters += "armeabi-v7a"
        }
    }

    buildTypes {
        release {
            // R8: shrink + obfuscate (libbox / Room / Hilt are kept by
            // proguard-rules.pro + their consumer rules).
            isMinifyEnabled = true
            isShrinkResources = true
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


    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:data"))
    implementation(project(":core:database"))
    implementation(project(":feature:profiles"))
    implementation(project(":feature:connection"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // QR import (batch 1, item #1): ZXing core, Apache-2.0, pure Java —
    // no ML Kit (rejected: model download + memory on a 2 GB phone).
    // Declared here per the batch instruction; the decoder itself lives
    // in :core:data, which declares the same artifact (see its
    // build.gradle.kts) so the module that compiles it owns it.
    implementation("com.google.zxing:core:3.5.3")
}
