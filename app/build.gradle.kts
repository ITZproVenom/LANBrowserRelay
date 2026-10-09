plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace = "com.lanbrowserrelay"
 compileSdk = 34
 defaultConfig { applicationId = "com.lanbrowserrelay"; minSdk = 24; targetSdk = 34; versionCode = 1; versionName = "1.0.0" }
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
