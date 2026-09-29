plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Inject git commit hash into BuildConfig
val gitHash = try {
    val proc = Runtime.getRuntime().exec(
        arrayOf("git", "rev-parse", "--short", "HEAD"),
        null, rootDir
    )
    proc.inputStream.bufferedReader().readText().trim()
} catch (_: Exception) { "dev" }

android {
    namespace = "com.fencewatcher.sonyanc"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fencewatcher.sonyanc"
        minSdk = 26
        targetSdk = 34
        // versionCode tracks versionName from v1.17 onward. Previously versionName
        // was pinned at "1.3" through v1.16, so the version on the device could not
        // tell you which build was installed.
        versionCode = 64
        versionName = "1.64"

        buildConfigField("String", "BUILD_HASH", "\"$gitHash\"")

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
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
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.11.0")
}