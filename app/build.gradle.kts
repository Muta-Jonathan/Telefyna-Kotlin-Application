plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.avventomedia.app.telefyna"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.avventomedia.app.telefyna"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // Enable code shrinking (minification)
            isMinifyEnabled = true
            // Use R8 for code shrinking (enabled by default in newer Android Gradle plugin versions)
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.media3.datasource.rtmp)
    implementation(libs.androidx.work.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation(libs.commons.io)
    implementation (libs.commons.lang3)
    implementation(libs.javax.mail)

    // added Gson
    implementation (libs.gson)
    // added Exoplayer
    implementation(libs.androidx.media3.exoplayer)
    // For HLS playback support with ExoPlayer
    implementation (libs.androidx.media3.exoplayer.hls)
    // For SmoothStreaming playback support with ExoPlayer
    implementation (libs.androidx.media3.exoplayer.smoothstreaming)
    // For RTSP playback support with ExoPlayer
    implementation (libs.androidx.media3.exoplayer.rtsp)
    // For srt playback support
    implementation (libs.srtdroid.core)
    // For loading data using librtmp
    implementation(libs.androidx.media3.datasource.rtmp)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.ui)

    // for loading images
    implementation (libs.glide)
}