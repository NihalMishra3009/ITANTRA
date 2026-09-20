import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    kotlin("kapt")
}

// Release signing is read from keystore.properties (git-ignored) or, in CI, from
// the ITANTRA_KEYSTORE_* environment variables. When neither is present the
// release build falls back to the debug key so `assembleRelease` still emits an
// INSTALLABLE apk for field/demo use instead of an unsigned one. A fallback-signed
// apk is fine for sideloading and demos; it is not a Play-distributable artifact.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

fun signingValue(key: String, env: String): String? =
    (keystoreProps.getProperty(key) ?: System.getenv(env))?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("storeFile", "ITANTRA_KEYSTORE_FILE")
val releaseStorePassword = signingValue("storePassword", "ITANTRA_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "ITANTRA_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "ITANTRA_KEY_PASSWORD")
val hasReleaseSigning = listOf(
    releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword
).all { it != null } && rootProject.file(releaseStoreFile!!).exists()

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

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
    packaging {
        jniLibs {
            // Only one libonnxruntime.so must ship — from sherpa onnx (same SONAME).
            pickFirsts.add("**/libonnxruntime.so")
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
