plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The app carries the same version as the computer side: VERSION= in bin/phone-mic.
// "2.0.0" -> versionName 2.0.0, versionCode 20000
val release = Regex("""(?m)^VERSION=([0-9.]+)$""").find(rootProject.file("../bin/phone-mic").readText())!!.groupValues[1]

android {
    namespace = "io.github.sharjeelmazhar.phonemic"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.sharjeelmazhar.phonemic"
        minSdk = 26
        targetSdk = 35
        versionName = release
        versionCode = release.split(".").map { it.toInt() }.let { (a, b, c) -> a * 10000 + b * 100 + c }
    }
    signingConfigs {
        // Key made by build.sh and kept out of git. Updates of the app must be signed with the same key.
        create("release") {
            storeFile = rootProject.file("phone-mic-release.jks")
            storePassword = "phone-mic-local"
            keyAlias = "phonemic"
            keyPassword = "phone-mic-local"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.fragment:fragment:1.8.5")          // the scanner brings an old one; activity results need >= 1.3
    // QR scanning through Google Play services: no camera permission, no camera code in this app
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    testImplementation("junit:junit:4.13.2")
}
