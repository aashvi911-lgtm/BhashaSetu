plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)

    id("com.chaquo.python")
}

android {
    namespace = "com.example.translation"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.translation"

        minSdk = 24
        targetSdk = 36

        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf(
                "arm64-v8a",
                "x86_64"
            )
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false

            proguardFiles(
                getDefaultProguardFile(
                    "proguard-android-optimize.txt"
                ),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }
}

// =====================================================
// CHAQUOPY / PYTHON
// =====================================================

chaquopy {
    defaultConfig {
        version = "3.10"

        buildPython("C:/Users/Aashv/AppData/Local/Programs/Python/Python310/python.exe")

        pip {
            install("numpy")
            install("sentencepiece")
        }
    }
}

// =====================================================
// DEPENDENCIES
// =====================================================

dependencies {
    //new:
    implementation("com.alphacephei:vosk-android:0.3.47")
    //
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation("androidx.activity:activity-compose:1.10.1")

    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    androidTestImplementation(
        libs.androidx.espresso.core
    )

    androidTestImplementation(
        platform(libs.androidx.compose.bom)
    )

    androidTestImplementation(
        libs.androidx.compose.ui.test.junit4
    )

    debugImplementation(
        libs.androidx.compose.ui.tooling
    )

    debugImplementation(
        libs.androidx.compose.ui.test.manifest
    )
}