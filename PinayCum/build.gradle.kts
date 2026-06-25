plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.lagradost"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly(files("../cloudstream.jar"))
}