import java.util.Properties

plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

fun String.asBuildConfigString(): String =
    "\"${replace("\\", "\\\\").replace("\"", "\\\"")}\""

val localProperties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.exists()) {
        propertiesFile.inputStream().use(::load)
    }
}

fun catalogConfig(environmentName: String, propertyName: String): String =
    providers.environmentVariable(environmentName).orNull
        ?.takeIf(String::isNotBlank)
        ?: localProperties.getProperty(propertyName, "")

val tidalClientId = catalogConfig("TIDAL_CLIENT_ID", "tidal.clientid")
val tidalClientScopes = catalogConfig("TIDAL_CLIENT_SCOPES", "tidal.clientscopes")
val tidalClientRedirectUri = catalogConfig("TIDAL_CLIENT_REDIRECT_URI", "tidal.clientredirecturi")

android {
    namespace = "com.loosewire.kelp.server"
    compileSdk = 36

    defaultConfig {
        minSdk = 34
        consumerProguardFiles("proguard-rules.pro")
        buildConfigField(
            "String",
            "CLIENT_ID",
            tidalClientId.asBuildConfigString(),
        )
        buildConfigField(
            "String",
            "CLIENT_SCOPES",
            tidalClientScopes.asBuildConfigString(),
        )
        buildConfigField(
            "String",
            "REDIRECT_URI",
            tidalClientRedirectUri.asBuildConfigString(),
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

val validateReleaseCatalogConfig by tasks.registering {
    group = "verification"
    description = "Fails release builds that do not contain the TIDAL catalog configuration"
    doLast {
        val missing = buildList {
            if (tidalClientId.isBlank()) add("TIDAL_CLIENT_ID or tidal.clientid")
            if (tidalClientScopes.isBlank()) add("TIDAL_CLIENT_SCOPES or tidal.clientscopes")
            if (tidalClientRedirectUri.isBlank()) {
                add("TIDAL_CLIENT_REDIRECT_URI or tidal.clientredirecturi")
            }
        }
        if (missing.isNotEmpty()) {
            throw GradleException("Missing TIDAL catalog configuration: ${missing.joinToString()}")
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(validateReleaseCatalogConfig)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":protocol"))
    implementation(libs.sdk.server)
    implementation(libs.sdk.client) {
        exclude(group = "com.google.mlkit")
        exclude(group = "androidx.camera")
        exclude(group = "com.google.crypto.tink", module = "tink")
    }
    implementation(libs.compose.activity)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.kotlinx.coroutines)
    // TIDAL Auth needs AndroidKeysetManager, so use the Android artifact matching UnifiedPush's Tink version.
    implementation("com.google.crypto.tink:tink-android:1.20.0")
    implementation("com.tidal.sdk:auth:0.10.1") {
        exclude(group = "com.google.crypto.tink", module = "tink-android")
    }
    implementation("com.tidal.sdk:tidalapi:0.3.53") {
        exclude(group = "com.google.crypto.tink", module = "tink-android")
    }
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(libs.kotlin.test)
}
