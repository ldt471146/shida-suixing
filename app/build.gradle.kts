import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Local, gitignored configuration: the release signing material and the built-in recognition
// endpoint. The keystore lives outside this repository, and local.properties is ignored, so a
// published tree never carries either one.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val visionBaseUrl: String = localProperties.getProperty("vision.baseUrl").orEmpty()
val visionApiKey: String = localProperties.getProperty("vision.apiKey").orEmpty()
val signingStoreFile: String? = localProperties.getProperty("signing.storeFile")
    ?.takeIf { it.isNotBlank() && rootProject.file(it).exists() }

android {
    namespace = "cn.gxnu.campus"
    compileSdk = 34

    defaultConfig {
        applicationId = "cn.gxnu.campus"
        minSdk = 26
        targetSdk = 34
        versionCode = 9
        versionName = "0.4.2"

        // Recognition endpoint compiled into the build so the shipped app needs no key entry.
        // Blank values drop the app back to the manual API-key screen.
        buildConfigField("String", "VISION_BASE_URL", "\"$visionBaseUrl\"")
        buildConfigField("String", "VISION_API_KEY", "\"$visionApiKey\"")
    }

    signingConfigs {
        if (signingStoreFile != null) {
            create("release") {
                storeFile = rootProject.file(signingStoreFile)
                storePassword = localProperties.getProperty("signing.storePassword")
                keyAlias = localProperties.getProperty("signing.keyAlias")
                keyPassword = localProperties.getProperty("signing.keyPassword")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // A missing keystore (for example a fork without signing material) still builds,
            // but the artifact is then only debug-signed and not distributable.
            signingConfig = if (signingStoreFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
