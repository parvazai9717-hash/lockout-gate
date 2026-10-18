import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Secrets live in android/local.properties (gitignored), never in source.
// See local.properties.example.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun secret(name: String): String = (localProps.getProperty(name) ?: System.getenv(name) ?: "").trim()

android {
    namespace = "com.lockout.gate"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lockout.gate"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"

        // The server URL and the device key (same value as the server's
        // LOCKOUT_DEVICE_KEY env var) come from android/local.properties.
        var baseUrl = secret("LOCKOUT_SERVER_BASE_URL")
        val deviceKey = secret("LOCKOUT_DEVICE_KEY")
        if (baseUrl.isBlank() || deviceKey.isBlank()) {
            throw GradleException(
                "Set LOCKOUT_SERVER_BASE_URL and LOCKOUT_DEVICE_KEY in android/local.properties " +
                    "(copy local.properties.example)."
            )
        }
        if (!baseUrl.endsWith("/")) baseUrl += "/" // Retrofit requires a trailing slash
        buildConfigField("String", "SERVER_BASE_URL", "\"$baseUrl\"")
        buildConfigField("String", "DEVICE_KEY", "\"$deviceKey\"")
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
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
}
