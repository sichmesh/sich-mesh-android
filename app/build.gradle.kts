// SICH Mesh for Android. No third-party libraries in the app itself: the Android SDK only
// (Bluetooth, the service, HTTPS), so nothing outside this repository runs inside the app.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.sichmesh.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.sichmesh.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true            // R8: smaller, names obfuscated
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // for now signed with the debug key (testing only); the SICH release key comes later and never enters git
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
