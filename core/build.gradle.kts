plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.wearlink.core"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":shared"))
    api(mapOf("name" to "libbox", "ext" to "aar"))
    api(libs.kotlinx.coroutines.android)
    api(libs.androidx.core.ktx)
    api(libs.androidx.activity)
    api(libs.androidx.work.runtime)
    implementation(libs.okhttp)
}
