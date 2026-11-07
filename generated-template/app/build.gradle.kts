plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val generatedAppId = providers.gradleProperty("generatedAppId")
    .orElse("com.tein.generated.sample")
    .get()
val generatedAppName = providers.gradleProperty("generatedAppName")
    .orElse("Generated App")
    .get()
val generatedVersionCode = providers.gradleProperty("generatedVersionCode")
    .orElse("1")
    .get()
val generatedVersionName = providers.gradleProperty("generatedVersionName")
    .orElse("1.0")
    .get()

android {
    namespace = "com.buildapp.generated"
    compileSdk = 35

    defaultConfig {
        applicationId = generatedAppId
        minSdk = 24
        targetSdk = 34
        versionCode = generatedVersionCode.toIntOrNull() ?: 1
        versionName = generatedVersionName
        resValue("string", "app_name", generatedAppName)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        debug {
            // GitHub Actions signs this variant with its automatically generated debug key.
            isDebuggable = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.12.1")
}
