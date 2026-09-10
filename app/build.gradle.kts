plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.foldblur"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.foldblur"
        minSdk = 31
        targetSdk = 35
        versionCode = 6
        versionName = "0.5.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
