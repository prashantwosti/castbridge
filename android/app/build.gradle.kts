import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing key, kept out of git: android/keystore.properties with storeFile,
// storePassword, keyAlias and keyPassword. Without it the release APK is left unsigned.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "app.prashant.castbridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.prashant.castbridge"
        minSdk = 29 // AudioPlaybackCapture
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        // Fixed name so github.com/<owner>/<repo>/releases/latest/download/castbridge-release.apk
        // (the TV app's QR code) always resolves to the newest release.
        base.archivesName = "castbridge"
    }

    sourceSets["main"].assets.srcDirs("../../receiver")

    signingConfigs {
        if (!keystoreProps.isEmpty) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Without keystore.properties the release APK is left unsigned.
            signingConfig = signingConfigs.findByName("release")
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
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Compose 1.10 line: the newest that builds with compileSdk 36 / AGP 8.
    val composeBom = platform("androidx.compose:compose-bom:2026.03.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.12.4")
}
