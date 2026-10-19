plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jetpaine.spooky"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jetpaine.spooky"
        // ARCore "AR Required" apps need API 24+.
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // ARCore SDK; the Depth API ships as part of it.
    implementation("com.google.ar:core:1.48.0")
}
