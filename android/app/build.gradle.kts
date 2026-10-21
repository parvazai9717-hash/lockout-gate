import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

val serverBaseUrl = localProperties.getProperty("LOCKOUT_SERVER_BASE_URL", "")
val deviceKey = localProperties.getProperty("LOCKOUT_DEVICE_KEY", "")

android {
    namespace = "com.lockout.gate"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lockout.gate"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.3.1"
        buildConfigField("boolean", "PERSONAL_SETTINGS_GUARD", "false")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "SERVER_BASE_URL", "\"$serverBaseUrl\"")
        buildConfigField("String", "DEVICE_KEY", "\"$deviceKey\"")
        buildConfigField("String", "FOCUS_DEVICE_ID", "\"${localProperties.getProperty("LOCKOUT_FOCUS_DEVICE_ID", "")}\"")
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "PERSONAL_SETTINGS_GUARD", "true")
            applicationIdSuffix = ".preview"
            versionNameSuffix = "-preview"
            resValue("string", "app_name", "Lockout Gate Preview")
        }
        release {
            // Never package the owner's personal server credentials in public artifacts.
            buildConfigField("String", "DEVICE_KEY", "\"\"")
            buildConfigField("String", "FOCUS_DEVICE_ID", "\"\"")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("publicPreview") {
            initWith(getByName("release"))
            applicationIdSuffix = ".publicpreview"
            versionNameSuffix = "-public-preview"
            isDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            resValue("string", "app_name", "Lockout Gate Public Test")
        }
    }

    buildFeatures {
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // Robolectric's native loader mishandles URL-escaped Windows paths.
            // A caller can supply a space-free Maven cache path for local tests.
            providers.gradleProperty("robolectricMavenRepo").orNull?.let { test.systemProperty("maven.repo.local", it) }
        }
    }
    sourceSets.getByName("test").java.srcDir("src/sharedTest/java")
    sourceSets.getByName("androidTest").java.srcDir("src/sharedTest/java")

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

    implementation("io.coil-kt:coil:2.6.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
