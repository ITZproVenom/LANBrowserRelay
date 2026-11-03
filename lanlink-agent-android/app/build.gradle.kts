plugins {
    id("com.android.application")
}

android {
    namespace = "com.itzprovenom.lanlinkagent"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.itzprovenom.lanlinkagent"
        minSdk = 23
        targetSdk = 35
        versionCode = 100
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
        }
    }
}
