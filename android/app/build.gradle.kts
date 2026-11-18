plugins {
    id("com.android.application")
}

android {
    namespace = "com.pocket.brain"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.pocket.brain"
        minSdk = 31
        targetSdk = 34
        versionCode = 14
        versionName = "1.4"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}
