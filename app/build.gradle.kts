plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.light.sdk)
}

android {
    compileSdk = 36

    signingConfigs {
        // Light's shared development key is required for local SDK-built APKs.
        create("lightsdkDev") {
            storeFile = file("../../light-sdk/sdk/keys/lightsdk-dev.jks")
            storePassword = "android"
            keyAlias = "lightsdk-dev"
            keyPassword = "android"
            enableV3Signing = true
            enableV4Signing = true
        }
        // GitHub Releases are signed with a dedicated release key supplied via
        // environment (keystore decoded from Actions secrets in CI).
        if (!providers.environmentVariable("TIDE_RELEASE_KEYSTORE").getOrElse("").isBlank()) {
            create("release") {
                storeFile = file(providers.environmentVariable("TIDE_RELEASE_KEYSTORE").get())
                storePassword = providers.environmentVariable("TIDE_RELEASE_STORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("TIDE_RELEASE_KEY_ALIAS")
                    .getOrElse("tide-release")
                keyPassword = providers.environmentVariable("TIDE_RELEASE_KEY_PASSWORD").get()
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    val hasReleaseKey = !providers.environmentVariable("TIDE_RELEASE_KEYSTORE")
        .getOrElse("")
        .isBlank()

    defaultConfig {
        minSdk = 34
        targetSdk = 36
        manifestPlaceholders["sdkVersion"] = property("sdkVersion") as String
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("lightsdkDev")
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("lightsdkDev")
            }
        }
    }

    lint {
        warningsAsErrors = false
        error += "RestrictedApi"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":protocol"))
    implementation(libs.sdk.client) {
        exclude(group = "com.google.mlkit")
        exclude(group = "androidx.camera")
        exclude(group = "com.google.crypto.tink", module = "tink")
    }
    implementation(libs.kotlinx.coroutines)

    // Keep the SDK client and Kelp's foreground media session on one tested
    // Media3 runtime; TIDAL Auth/API do not own the player.
    listOf(
        "media3-common",
        "media3-exoplayer",
        "media3-session",
        "media3-exoplayer-dash",
        "media3-exoplayer-hls",
        "media3-datasource-okhttp",
    ).forEach { module ->
        implementation("androidx.media3:$module") {
            version { strictly("1.5.0") }
            because("Keep the SDK client and Kelp playback service on the same Media3 runtime")
        }
    }

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)

    // Merge the unrestricted server into the single tool APK without putting
    // server or official TIDAL SDK types on the app's compile classpath.
    debugRuntimeOnly(project(":server"))
    releaseRuntimeOnly(project(":server"))
}
