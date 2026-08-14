plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
    id("com.google.devtools.ksp")
}

val releaseStorePath = System.getenv("BORSAPATTERN_KEYSTORE_PATH")
val releaseStorePassword = System.getenv("BORSAPATTERN_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("BORSAPATTERN_KEY_ALIAS")
val releaseKeyPassword = System.getenv("BORSAPATTERN_KEY_PASSWORD")

android {
    namespace = "com.borsapattern.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.borsapattern.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 41
        versionName = "2.8.2-safe"
    }

    signingConfigs {
        create("borsaRelease") {
            if (!releaseStorePath.isNullOrBlank()) {
                storeFile = file(releaseStorePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (!releaseStorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("borsaRelease")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.2")

    implementation(platform("androidx.compose:compose-bom:2026.01.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")

    implementation("androidx.work:work-runtime-ktx:2.11.0")

    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    implementation("com.squareup.okhttp3:okhttp:5.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
