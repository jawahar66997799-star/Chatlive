plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun buildConfigString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.jawahar.livesync"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jawahar.livesync"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0-host-stream"

        buildConfigField(
            "String",
            "DEFAULT_RELAY_URL",
            buildConfigString(providers.gradleProperty("JLS_RELAY_URL").orElse("").get())
        )
        buildConfigField(
            "String",
            "DEFAULT_ROOM",
            buildConfigString(providers.gradleProperty("JLS_ROOM").orElse("").get())
        )
        buildConfigField(
            "String",
            "DEFAULT_HOST_TOKEN",
            buildConfigString(providers.gradleProperty("JLS_HOST_TOKEN").orElse("").get())
        )
        buildConfigField(
            "String",
            "DEFAULT_GUEST_BASE_URL",
            buildConfigString(providers.gradleProperty("JLS_GUEST_BASE_URL").orElse("").get())
        )
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.jaredmdobson:concentus:1.0.2")

    testImplementation("junit:junit:4.13.2")
}
