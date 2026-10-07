import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release key from keystore.properties (not in git). Updates install only over APKs signed with it.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "dev.wearlink.wear"
    compileSdk = 37

    defaultConfig {
        // Same id as the phone app: required for the Wearable Data Layer.
        applicationId = "dev.wearlink"
        minSdk = 30
        targetSdk = 36
        versionCode = providers.gradleProperty("wearlink.versionCode").get().toInt()
        versionName = providers.gradleProperty("wearlink.versionName").get()
        buildConfigField("String", "UPDATE_BASE_URL", "\"${providers.gradleProperty("wearlink.updateBaseUrl").get()}\"")
    }

    signingConfigs {
        if (!keystoreProps.isEmpty) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }
    val appSigning = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")

    buildTypes {
        debug {
            signingConfig = appSigning
            // Real watches plus x86_64 for the emulators.
            ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64") }
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = appSigning
            // Galaxy Watch 8 runs a 32-bit userspace (armeabi-v7a) on its 64-bit CPU; keep arm64 for other watches.
            ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // libbox.so is ~60 MB uncompressed; store it compressed in the APK.
    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.navigation)
    implementation(libs.wear.tiles)
    implementation(libs.wear.protolayout)
    implementation(libs.wear.protolayout.expression)
    implementation(libs.wear.remote.interactions)
    implementation(libs.wear.input)
    implementation(libs.androidx.concurrent.futures)
    implementation(libs.play.services.wearable)
    implementation(libs.kotlinx.coroutines.play.services)
}
