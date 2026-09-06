plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "ir.aut.supplementtracker.core.data"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 24
        buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:3000/v1/\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
}
