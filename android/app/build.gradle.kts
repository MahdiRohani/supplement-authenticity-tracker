plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

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
            buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:3000/v1/\"")
            buildConfigField("String", "RPC_URL", "\"http://10.0.2.2:8545\"")
            buildConfigField(
                "String",
                "REGISTRY_ADDRESS",
                "\"0x5FbDB2315678afecb367f032d93F642f64180aa3\"",
            )
            buildConfigField("String", "SIGNING_MODE", "\"managed\"")
        }
        create("sepolia") {
            dimension = "env"
            buildConfigField("String", "API_BASE_URL", "\"https://api.sepolia.placeholder/v1/\"")
            buildConfigField("String", "RPC_URL", "\"https://sepolia.placeholder.rpc\"")
            buildConfigField("String", "REGISTRY_ADDRESS", "\"0x0000000000000000000000000000000000000000\"")
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
            pickFirsts += "META-INF/**"
            excludes += setOf(
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
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
