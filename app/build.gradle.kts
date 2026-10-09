plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

val releaseVersion = providers.gradleProperty("releaseVersion").orNull
val appVersion = releaseVersion ?: "0.1.0-dev"
val stableVersionPattern = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")
val developmentVersionPattern = Regex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)-dev$")
val versionMatch = stableVersionPattern.matchEntire(appVersion) ?: developmentVersionPattern.matchEntire(appVersion)
    ?: error("Version must be MAJOR.MINOR.PATCH (development builds may use -dev): $appVersion")
if (releaseVersion != null && stableVersionPattern.matchEntire(releaseVersion) == null) {
    error("releaseVersion must be a validated stable MAJOR.MINOR.PATCH value")
}
val major = versionMatch.groupValues[1].toLong()
val minor = versionMatch.groupValues[2].toLong()
val patch = versionMatch.groupValues[3].toLong()
if (minor > 999 || patch > 999 || major > 2147) error("Version components exceed the monotonic Android versionCode range")
val versionCodeLong = major * 1_000_000L + minor * 1_000L + patch
if (versionCodeLong <= 0 || versionCodeLong > Int.MAX_VALUE) error("Version cannot be represented by a positive Android versionCode")
val derivedVersionCode = versionCodeLong.toInt()

val releaseKeystore = System.getenv("LBR_KEYSTORE_PATH")
val releaseStorePassword = System.getenv("LBR_KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("LBR_KEY_ALIAS")
val releaseKeyPassword = System.getenv("LBR_KEY_PASSWORD")
val releaseSigningReady = listOf(releaseKeystore, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
    .all { !it.isNullOrBlank() }

gradle.taskGraph.whenReady {
    val packagesRelease = allTasks.any { it.name == "assembleRelease" || it.name == "bundleRelease" }
    if (packagesRelease) {
        if (releaseVersion == null) error("Release packaging requires -PreleaseVersion from a validated release tag")
        if (!releaseSigningReady) error("Release packaging requires persistent LBR_KEYSTORE_* signing credentials")
    }
}

android {
    namespace = "com.lanbrowserrelay"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.lanbrowserrelay"
        minSdk = 24
        targetSdk = 34
        versionCode = derivedVersionCode
        versionName = appVersion
    }
    signingConfigs {
        getByName("debug")
        if (releaseSigningReady) {
            create("release") {
                storeFile = file(releaseKeystore!!)
                storePassword = releaseStorePassword!!
                keyAlias = releaseKeyAlias!!
                keyPassword = releaseKeyPassword!!
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningReady) signingConfig = signingConfigs.getByName("release")
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