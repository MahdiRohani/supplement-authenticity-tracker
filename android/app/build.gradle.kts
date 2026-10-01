plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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

// 10.0.2.2 only resolves to the dev machine inside the emulator; a physical phone
// needs 127.0.0.1 (with `adb reverse`) or the machine's LAN IP.
val localDevHost = localProp("LOCAL_DEV_HOST", "10.0.2.2")

android {
    namespace = "ir.aut.supplementtracker"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "ir.aut.supplementtracker"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "env"
    productFlavors {
        create("local") {
            dimension = "env"
            buildConfigField("String", "API_BASE_URL", "\"http://$localDevHost:3000/v1/\"")
            buildConfigField("String", "RPC_URL", "\"http://$localDevHost:8545\"")
            buildConfigField(
                "String",
                "REGISTRY_ADDRESS",
                "\"0x5FbDB2315678afecb367f032d93F642f64180aa3\"",
            )
            buildConfigField("String", "SIGNING_MODE", "\"managed\"")
        }
        create("sepolia") {
            dimension = "env"
            buildConfigField(
                "String",
                "API_BASE_URL",
                "\"${localProp("SEPOLIA_API_BASE_URL", "https://api.sepolia.example.invalid/v1/")}\"",
            )
            buildConfigField(
                "String",
                "RPC_URL",
                "\"${localProp("SEPOLIA_RPC_URL", "https://rpc.sepolia.example.invalid")}\"",
            )
            buildConfigField(
                "String",
                "REGISTRY_ADDRESS",
                "\"${localProp("SEPOLIA_REGISTRY_ADDRESS", "0x0000000000000000000000000000000000000000")}\"",
            )
            buildConfigField("String", "SIGNING_MODE", "\"relayer\"")
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            // Signature files from signed jars (BouncyCastle via web3j) must never reach
            // the APK: next to a foreign MANIFEST.MF they make every classpath resource
            // read throw SecurityException (this crashed ML Kit on the scan screen).
            excludes += setOf(
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "META-INF/*.EC",
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                // Build-time metadata duplicated across server-side jars (Jackson, AWS SDK,
                // Netty); none of it is read at runtime on Android.
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*-LICENSE*",
                "META-INF/*-NOTICE*",
                "META-INF/license/**",
                "META-INF/DISCLAIMER",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/*.md",
                "META-INF/versions/*/OSGI-INF/**",
                "META-INF/native-image/**",
                "META-INF/io.netty.versions.properties",
                "META-INF/versions/*/module-info.class",
                "module-info.class",
            )
        }
    }
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(project(":core:blockchain"))
    implementation(project(":feature:manufacturer-register"))
    implementation(project(":feature:manufacturer-dashboard"))
    implementation(project(":feature:transfer"))
    implementation(project(":feature:history"))
    implementation(project(":feature:verify"))
    implementation(project(":feature:consume"))
    implementation(project(":feature:stock"))
    implementation(project(":feature:scan"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
