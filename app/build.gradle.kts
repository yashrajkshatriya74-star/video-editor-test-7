plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.videoeditor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.videoeditor"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes.add("META-INF/*")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.2")

    // --- Video editing: Media3 (Google's official replacement for ExoPlayer + editing APIs) ---
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-transformer:1.4.1")   // trimming, splitting, transcoding
    implementation("androidx.media3:media3-effect:1.4.1")        // filters/effects pipeline
    implementation("androidx.media3:media3-common:1.4.1")

    // --- Chroma key / custom GL effects ---
    // Uses platform OpenGL ES (android.opengl.*) — no extra dependency needed,
    // see ChromaKeyRenderer.kt for the shader implementation.

    // --- 3D model import (glTF/GLB/OBJ) ---
    implementation("com.google.android.filament:filament-android:1.51.6")
    implementation("com.google.android.filament:gltfio-android:1.51.6")
    implementation("com.google.android.filament:filament-utils-android:1.51.6")

    // --- Optional: FFmpegKit for advanced split/trim/mux if Media3 Transformer isn't enough ---
    // implementation("com.arthenica:ffmpeg-kit-full:6.0-2")  // add if you need format coverage beyond Media3

    // --- File picking / permissions ---
    implementation("androidx.activity:activity-ktx:1.9.2")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
