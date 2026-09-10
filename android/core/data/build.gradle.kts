plugins {
    alias(libs.plugins.android.library)
}

import java.util.Properties

val localProps =
    Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) {
            file.inputStream().use { load(it) }
        }
    }

fun localProp(key: String, default: String): String =
    localProps.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() } ?: default

android {
    namespace = "ir.aut.supplementtracker.core.data"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 24
    }
    flavorDimensions += "env"
    productFlavors {
        create("local") {
            dimension = "env"
            buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:3000/v1/\"")
            buildConfigField("String", "SIGNING_MODE", "\"managed\"")
        }
        create("sepolia") {
            dimension = "env"
            buildConfigField(
                "String",
                "API_BASE_URL",
                "\"${localProp("SEPOLIA_API_BASE_URL", "https://api.sepolia.example.invalid/v1/")}\"",
            )
            buildConfigField("String", "SIGNING_MODE", "\"relayer\"")
        }
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
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    testImplementation(libs.junit)
}
