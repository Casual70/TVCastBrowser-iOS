plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.tvcast.browser"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tvcast.browser"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.4.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // Disabilita il blocco proprietario DEPENDENCY_INFO_BLOCK di Google per piena conformità F-Droid / IzzyOnDroid
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    signingConfigs {
        create("release") {
            val keystoreFile = rootProject.file("keystore/tvcast-release.jks")
            if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "tvcast2026"
                keyAlias = System.getenv("KEY_ALIAS") ?: "tvcast"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "tvcast2026"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            val keystoreFile = rootProject.file("keystore/tvcast-release.jks")
            if (keystoreFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.appcompat)

    // Enhanced WebView capabilities (Open Source AndroidX WebKit)
    implementation(libs.androidx.webkit)

    debugImplementation(libs.androidx.ui.tooling)
}