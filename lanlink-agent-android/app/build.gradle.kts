plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.itzprovenom.lanlinkagent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.itzprovenom.lanlinkagent"
        minSdk = 23
        targetSdk = 35
        versionCode = 304
        versionName = "0.3.4"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("int", "LOCAL_CONTROL_PORT", "43822")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("int", "LOCAL_CONTROL_PORT", "43821")
        }
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.github.rhythmcache.adb-kt:lib:394dea235b4bfb2ebbfba5a821bbe4f6cd59e460")
}
