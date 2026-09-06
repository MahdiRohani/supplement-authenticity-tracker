plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "ir.aut.supplementtracker.core.model"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
