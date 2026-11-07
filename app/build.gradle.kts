plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wwm.midiplayer.pro"
    compileSdk = 34

    val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0

    defaultConfig {
        applicationId = "com.wwm.midiplayer.pro"
        minSdk = 26
        targetSdk = 34
        versionCode = 200 + runNumber
        versionName = "1.0.${8 + runNumber}"
    }

    signingConfigs {
        val keystoreFile = file("wewish_signing.jks")
        if (keystoreFile.exists()) {
            getByName("debug") {
                storeFile = keystoreFile
                storePassword = "wewish123456"
                keyAlias = "wewish_key"
                keyPassword = "wewish123456"
            }
            create("release") {
                storeFile = keystoreFile
                storePassword = "wewish123456"
                keyAlias = "wewish_key"
                keyPassword = "wewish123456"
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            if (signingConfigs.findByName("release") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
