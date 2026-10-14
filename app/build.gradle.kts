plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.cobra.busantravel"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cobra.busantravel"
        minSdk = 26
        targetSdk = 36
        // Version 2 is reserved for the temporary dev-back1 recovery update.
        // The next full travel build must be newer so it can update recovery.
        versionCode = 3
        versionName = "0.3"
    }

    signingConfigs {
        create("workspaceDebug") {
            storeFile = rootProject.file("../nodelete/android-signing/dev-busan-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("workspaceDebug") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
