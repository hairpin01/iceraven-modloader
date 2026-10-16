plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.iceravenmodule"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.iceravenmodule"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    sourceSets {
        getByName("main") {
            // The SPI sources are compiled straight into the module archive. At runtime the browser class loader is the
            // parent of the module class loader, so the browser's own copy of these interfaces is the one that is used.
            kotlin.srcDir("../../iceraven-browser/app/src/main/java/org/mozilla/fenix/modules/api")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        // Sign with the same key as the browser build to avoid having to enable developer mode.
        // Fill in your own keystore, or drop the signingConfig line and use developer mode instead.
        create("module") {
            storeFile = file(System.getenv("MODULE_KEYSTORE") ?: "module.jks")
            storePassword = System.getenv("MODULE_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("MODULE_KEY_ALIAS")
            keyPassword = System.getenv("MODULE_KEY_PASSWORD")
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("module")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}