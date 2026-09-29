import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing. `keystore.properties` and the `.jks` are local-only and gitignored, so a
// fresh clone — or CI — still builds; it simply produces an unsigned release APK.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasSigningConfig = keystoreProperties.getProperty("storeFile") != null

android {
    namespace = "com.xudong.wubitrainer"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.xudong.wubitrainer"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                // AGP 9 signs with the HIGHEST enabled scheme and only that one, so enabling v3
                // means v1 and v2 both report false from `apksigner` — measured, not assumed.
                // That is correct here: v3 has existed since Android 9 and minSdk is 30, so
                // every device this APK can install on reads it. (Enabling v2 as well has no
                // effect, which is why it is not set.)
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // A distinct id so a debug build can sit alongside the release app on the same
            // phone. The two do not share progress.
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
            // No shrinking: the app is small, entirely reflection-free and we would
            // rather ship exactly the code we tested.
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
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    // Icons for the bottom navigation bar. Core only — the extended pack is thousands of vectors
    // for the three glyphs we actually use.
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
