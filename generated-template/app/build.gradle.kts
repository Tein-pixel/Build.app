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
val generatedReleaseSigningEnabled = providers.gradleProperty("generatedReleaseSigningEnabled")
    .orElse("false")
    .get()
    .toBoolean()
val generatedReleaseStoreFile = providers.gradleProperty("generatedReleaseStoreFile").orNull
val generatedReleaseStorePassword = providers.gradleProperty("generatedReleaseStorePassword").orNull
val generatedReleaseKeyAlias = providers.gradleProperty("generatedReleaseKeyAlias").orNull
val generatedReleaseKeyPassword = providers.gradleProperty("generatedReleaseKeyPassword").orNull

if (generatedReleaseSigningEnabled) {
    require(!generatedReleaseStoreFile.isNullOrBlank()) { "Release keystore path is missing." }
    require(!generatedReleaseStorePassword.isNullOrBlank()) { "Release keystore password is missing." }
    require(!generatedReleaseKeyAlias.isNullOrBlank()) { "Release key alias is missing." }
    require(!generatedReleaseKeyPassword.isNullOrBlank()) { "Release key password is missing." }
}

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

    signingConfigs {
        if (generatedReleaseSigningEnabled) {
            create("generatedRelease") {
                storeFile = file(requireNotNull(generatedReleaseStoreFile))
                storePassword = requireNotNull(generatedReleaseStorePassword)
                keyAlias = requireNotNull(generatedReleaseKeyAlias)
                keyPassword = requireNotNull(generatedReleaseKeyPassword)
            }
        }
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
            // Temporary signing for development builds only.
            isDebuggable = false
        }
        release {
            isDebuggable = false
            isMinifyEnabled = false
            if (generatedReleaseSigningEnabled) {
                signingConfig = signingConfigs.getByName("generatedRelease")
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.12.1")
}
