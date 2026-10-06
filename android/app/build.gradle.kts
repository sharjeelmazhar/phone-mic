plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
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
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
