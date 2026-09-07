plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "ir.aut.supplementtracker.core.blockchain"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 24
        buildConfigField("String", "RPC_URL", "\"http://10.0.2.2:8545\"")
        buildConfigField(
            "String",
            "REGISTRY_ADDRESS",
            "\"0x5FbDB2315678afecb367f032d93F642f64180aa3\"",
        )
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
