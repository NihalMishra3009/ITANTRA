plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    kotlin("kapt")
}

android {
    namespace = "com.itantra"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.itantra"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a"))
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
        debug {
            isDebuggable = true
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
        viewBinding = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Ensure libitantra_mt.so is packaged for both target ABIs (and that the
    // sherpa-bundled libonnxruntime.so is the single ORT copy on device).
    packagingOptions {
        jniLibs {
            // Only one libonnxruntime.so must ship — from sherpa onnx (same SONAME).
            pickFirst("**/libonnxruntime.so")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    androidResources {
        noCompress.addAll(listOf("onnx", "tflite", "bin", "json", "vocab", "tokens", "raw", "wav", "txt"))
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.gson)
    implementation(libs.commons.compress)

    // sherpa-onnx: Whisper multilingual ASR + VITS/MMS TTS + ONNX Runtime (bundled) (Apache 2.0)
    implementation(files("libs/sherpa-onnx-1.13.7.aar"))

    // Room: persistent store-and-forward outbox
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
