plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.videoke.pro"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.videoke.pro"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "4.0-alpha"
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    buildTypes { getByName("release") { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget="17" }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    packaging { jniLibs.useLegacyPackaging = true }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.media3:media3-common:1.5.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("dev.ffmpegkit-maintained:whisper-android:1.0.0")
}
