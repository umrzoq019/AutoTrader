plugins { id("com.android.application") }
android {
    namespace = "com.autotrader.mobile"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.autotrader.mobile.v9"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "9.0"
    }
    buildTypes { getByName("release") { isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
