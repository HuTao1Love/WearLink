import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

fun secret(env: String, property: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: localProperties.getProperty(property)?.takeIf { it.isNotBlank() }

// Release signing. CI passes WEARLINK_KEYSTORE* variables (see .github/workflows/release.yml);
// locally put signing.storeFile/storePassword/keyAlias/keyPassword into local.properties.
// Updates install only over APKs signed with the same key.
val releaseKeystore = secret("WEARLINK_KEYSTORE", "signing.storeFile")

// CI passes the release version and run number; local builds use appVersion (gradle.properties) and 1.
val appVersionCode = providers.gradleProperty("versionCode").orNull?.toIntOrNull() ?: 1
val appVersionName = providers.gradleProperty("versionName").orNull ?: providers.gradleProperty("appVersion").get()

android {
    namespace = "dev.wearlink.mobile"
    compileSdk = 37

    defaultConfig {
        // Must match the watch app: the Data Layer only connects apps with the same id and signature.
        applicationId = "dev.wearlink"
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "UPDATE_BASE_URL", "\"${providers.gradleProperty("wearlink.updateBaseUrl").get()}\"")
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = rootProject.file(releaseKeystore)
                storePassword = secret("WEARLINK_KEYSTORE_PASSWORD", "signing.storePassword")
                keyAlias = secret("WEARLINK_KEY_ALIAS", "signing.keyAlias")
                keyPassword = secret("WEARLINK_KEY_PASSWORD", "signing.keyPassword")
            }
        }
    }
    val appSigning = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")

    buildTypes {
        debug {
            signingConfig = appSigning
            // arm64 for real devices, x86_64 for the emulators.
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = appSigning
            // Real phones and the Galaxy Watch are arm64.
            ndk { abiFilters += "arm64-v8a" }
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
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.play.services.wearable)
    implementation(libs.play.services.code.scanner)
    // play-services pulls an old Fragment that breaks the Activity Result API.
    implementation(libs.androidx.fragment)
    implementation(libs.kotlinx.coroutines.play.services)
}
