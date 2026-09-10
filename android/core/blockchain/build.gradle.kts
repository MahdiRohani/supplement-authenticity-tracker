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
    namespace = "ir.aut.supplementtracker.core.blockchain"
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
            buildConfigField("String", "RPC_URL", "\"http://10.0.2.2:8545\"")
            buildConfigField(
                "String",
                "REGISTRY_ADDRESS",
                "\"0x5FbDB2315678afecb367f032d93F642f64180aa3\"",
            )
            buildConfigField("String", "CHAIN_ID", "\"31337\"")
            buildConfigField("String", "SIGNING_MODE", "\"managed\"")
            buildConfigField(
                "String",
                "MANAGED_PRIVATE_KEY",
                "\"0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80\"",
            )
        }
        create("sepolia") {
            dimension = "env"
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
            buildConfigField("String", "CHAIN_ID", "\"11155111\"")
            buildConfigField("String", "SIGNING_MODE", "\"relayer\"")
            buildConfigField("String", "MANAGED_PRIVATE_KEY", "\"\"")
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
    implementation(libs.web3j.core)
    implementation(libs.kotlinx.coroutines.android)
}
