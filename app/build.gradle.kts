plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fencewatcher.sonyanc"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fencewatcher.sonyanc"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

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

    // Inject git commit hash into BuildConfig
    val gitHash = try {
        "git rev-parse --short HEAD".run {
            java.io.File(project.rootDir, "../").let { dir ->
                ProcessBuilder(*split(" ").toTypedArray())
                    .directory(dir)
                    .start()
                    .inputStream.bufferedReader().readText().trim()
            }
        }
    } catch (_: Exception) { "dev" }

    defaultConfig {
        buildConfigField("String", "BUILD_HASH", "\"$gitHash\"")
        buildConfigField("String", "BUILD_TIME", "\"${System.currentTimeMillis()}\"")
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.11.0")
}