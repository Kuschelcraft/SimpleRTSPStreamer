plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kuschelcraft.simplertspstreamer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kuschelcraft.simplertspstreamer"
        minSdk = 26
        targetSdk = 35
        // CI passes a monotonically increasing run number so newer builds always install over older ones.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.0.0"
    }

    // The repository ships a fixed signing key so that every build can be installed as an update
    // over the previous one. This is a sideloading key for a private tool, not a Play Store key.
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/release.jks")
            storePassword = "simplertsp"
            keyAlias = "simplertsp"
            keyPassword = "simplertsp"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
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
        viewBinding = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(project(":rtsp"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
}
