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

    // The SICH release key. It is never in this repository: GitHub Actions takes it from the repository's
    // secrets (see .github/workflows/build.yml), a developer's own machine from these environment variables.
    // Without them (a fork, a pull request, a local build) the APK is signed with the debug key: test builds only.
    signingConfigs {
        val ks = System.getenv("SICH_KEYSTORE")
        if (!ks.isNullOrEmpty()) create("sich") {
            storeFile = file(ks)
            storePassword = System.getenv("SICH_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("SICH_KEY_ALIAS")
            keyPassword = System.getenv("SICH_KEYSTORE_PASSWORD")   // PKCS12 (keytool's default): one password for both
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true            // R8: smaller, names obfuscated
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("sich") ?: signingConfigs.getByName("debug")
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
