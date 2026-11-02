plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.vifora.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vifora.app"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            // Provide via ~/.gradle/gradle.properties (NOT the repo one):
            //   VIFORA_STORE_FILE=/abs/path/vifora-release.jks
            //   VIFORA_STORE_PASSWORD=...
            //   VIFORA_KEY_ALIAS=vifora
            //   VIFORA_KEY_PASSWORD=...
            val storeFileProp = project.findProperty("VIFORA_STORE_FILE") as String?
            if (!storeFileProp.isNullOrBlank()) {
                storeFile = file(storeFileProp)
                storePassword = project.findProperty("VIFORA_STORE_PASSWORD") as String?
                keyAlias = project.findProperty("VIFORA_KEY_ALIAS") as String?
                keyPassword = project.findProperty("VIFORA_KEY_PASSWORD") as String?
            }
        }
    }
    buildTypes {
        release {
            // Unsigned unless VIFORA_STORE_FILE is set (see signingConfigs above).
            val storeFileProp = project.findProperty("VIFORA_STORE_FILE") as String?
            if (!storeFileProp.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.10.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Media3 (ExoPlayer) for preview / scrubber / crop+trim
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")

    // FFmpeg engine (full GPL, maintained fork — drop-in for archived com.arthenica,
    // same com.arthenica.ffmpegkit API). Original com.arthenica:6.0 artifacts 404 on Central.
    implementation("dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl:8.1.9")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
}
