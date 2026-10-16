plugins {
    // AGP 9 has built-in Kotlin support: the org.jetbrains.kotlin.android plugin must not be applied.
    id("com.android.application") version "9.4.0"
}

android {
    namespace = "com.example.geckomod"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.geckomod"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    sourceSets {
        getByName("main") {
            // The SPI sources are compiled straight into the module archive. At runtime the browser class loader is the
            // parent of the module class loader, so the browser's own copy of these interfaces is the one that is used.
            kotlin.srcDir("../../app/src/main/java/org/mozilla/fenix/modules/api")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Release archives are signed with your own key, the same one the browser build uses. Without it, enable developer
    // mode in Settings > Modules instead. The keystore is only configured when the environment provides one, so that
    // `./gradlew :app:assembleDebug` works out of the box.
    System.getenv("MODULE_KEYSTORE")?.let { keystore ->
        signingConfigs {
            create("module") {
                storeFile = file(keystore)
                storePassword = System.getenv("MODULE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("MODULE_KEY_ALIAS")
                keyPassword = System.getenv("MODULE_KEY_PASSWORD")
            }
        }
        buildTypes {
            getByName("release") {
                signingConfig = signingConfigs.getByName("module")
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}