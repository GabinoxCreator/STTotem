plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "br.com.st.totem"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.com.st.totem"
        minSdk = 26
        targetSdk = 35
        versionCode = 23
        versionName = "1.23.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // arm64-v8a = SK-210 (Gertec) | armeabi-v7a = fallback 32-bit
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
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

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "META-INF/MANIFEST.MF"
        }
        // CRÍTICO para SK-210: extrai as .so do APK para o filesystem.
        // Sem isso o Android não consegue fazer dlopen nas libs do CliSiTef JNI.
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Bibliotecas locais
    implementation(mapOf("name" to "EasyLayer-SK210-v2.1.7-release", "ext" to "aar"))
    implementation(mapOf("name" to "4.0.2-release", "ext" to "aar"))
    implementation(files("libs/clisitef-android-debug.jar"))
    implementation(mapOf("name" to "DecodeLibrary_1.8.03.A28", "ext" to "aar"))
    implementation(mapOf("name" to "image-1.9.5", "ext" to "aar"))

    // Camera + ML Kit
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.mlkit:face-detection:16.1.7")

    // AWS Amplify Face Liveness
    implementation("com.amplifyframework:core:2.24.0")
    implementation("com.amplifyframework.ui:liveness:1.4.0")
    implementation("com.amplifyframework:aws-auth-cognito:2.24.0")

    // Jetpack Compose
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}