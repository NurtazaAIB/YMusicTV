import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val encodedTestKey = rootProject.file(".github/ymusictv-test.keystore.b64")
val stableTestKey = layout.buildDirectory.file("ymusictv-test.keystore").get().asFile
if (encodedTestKey.exists()) {
    stableTestKey.parentFile.mkdirs()
    stableTestKey.writeBytes(Base64.getDecoder().decode(encodedTestKey.readText().trim()))
}

android {
    namespace = "dev.ymusictv"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.ymusictv"
        minSdk = 28
        targetSdk = 35
        versionCode = 22
        versionName = "0.9.8"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        create("stableDebug") {
            storeFile = stableTestKey
            storePassword = "ymusictvtest"
            keyAlias = "ymusictvtest"
            keyPassword = "ymusictvtest"
        }
    }

    buildTypes {
        debug {
            if (stableTestKey.exists()) signingConfig = signingConfigs.getByName("stableDebug")
        }
    }

    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
