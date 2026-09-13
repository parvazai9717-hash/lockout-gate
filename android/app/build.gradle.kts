plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lockout.gate"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lockout.gate"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Same secret as the server's LOCKOUT_DEVICE_KEY env var, and the
        // server's base URL. Edit these for your own deployment before
        // building — see Config.kt for the single source of truth used at
        // runtime (these just seed its defaults).
        // Must end with a trailing slash — Retrofit requires it.
        buildConfigField("String", "SERVER_BASE_URL", "\"https://lockout-gate-lockout-gate.qt5ga9.easypanel.host/\"")
        buildConfigField("String", "DEVICE_KEY", "\"97931aeac1fe752665cf6ffb950e31e48fde30234bd44e64\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
}
