plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

val releaseKeystore: String? = System.getenv("LBR_KEYSTORE_PATH")

android {
    namespace = "com.lanbrowserrelay"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.lanbrowserrelay"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }
    signingConfigs {
        getByName("debug")
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("LBR_KEYSTORE_PASSWORD") ?: error("LBR_KEYSTORE_PASSWORD is required when LBR_KEYSTORE_PATH is set")
                keyAlias = System.getenv("LBR_KEY_ALIAS") ?: error("LBR_KEY_ALIAS is required when LBR_KEYSTORE_PATH is set")
                keyPassword = System.getenv("LBR_KEY_PASSWORD") ?: error("LBR_KEY_PASSWORD is required when LBR_KEYSTORE_PATH is set")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            // No production keystore is committed to the repository. Without
            // LBR_KEYSTORE_* the release build is signed with the debug key so it
            // is installable; with a real keystore supplied it is properly signed.
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
        }
        debug { isMinifyEnabled = false }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
}