import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val localProperties = Properties()
val localFile = rootProject.file("local.properties")
if (localFile.exists()) {
    localFile.inputStream().use { localProperties.load(it) }
}

fun localOrEmpty(key: String): String {
    var v = (localProperties.getProperty(key) ?: "").replace("\r", "").replace("\n", "").trim()
    if (v.length >= 2) {
        val quote = v.first()
        if ((quote == '"' || quote == '\'') && v.last() == quote) {
            v = v.substring(1, v.lastIndex).replace("\r", "").replace("\n", "").trim()
        }
    }
    return v.replace("\\", "\\\\").replace("\"", "\\\"")
}

android {
    namespace = "de.rolfwalker.flightbuddy"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.rolfwalker.flightbuddy"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "SEED_OPENSKY_USERNAME", "\"${localOrEmpty("OPENSKY_USERNAME")}\"")
        buildConfigField("String", "SEED_OPENSKY_PASSWORD", "\"${localOrEmpty("OPENSKY_PASSWORD")}\"")
        buildConfigField("String", "SEED_OPENSKY_CLIENT_ID", "\"${localOrEmpty("OPENSKY_CLIENT_ID")}\"")
        buildConfigField("String", "SEED_OPENSKY_CLIENT_SECRET", "\"${localOrEmpty("OPENSKY_CLIENT_SECRET")}\"")
        buildConfigField("String", "SEED_AERODATABOX_KEY", "\"${localOrEmpty("AERODATABOX_KEY")}\"")
        buildConfigField("String", "SEED_AERODATABOX_HOST", "\"${localOrEmpty("AERODATABOX_HOST")}\"")
        buildConfigField(
            "String",
            "SEED_AERODATABOX_BASE_URL",
            "\"${
                localOrEmpty("AERODATABOX_BASE_URL").ifBlank {
                    val host = localOrEmpty("AERODATABOX_HOST")
                    when {
                        host.startsWith("http://") || host.startsWith("https://") -> host
                        host.contains("api.market") && host.contains("/api/v1/") -> "https://$host"
                        else -> "https://prod.api.market/api/v1/aedbx/aerodatabox"
                    }
                }
            }\"",
        )
        buildConfigField("String", "SEED_FR24_API_TOKEN", "\"${localOrEmpty("FR24_API_TOKEN")}\"")
        buildConfigField("String", "SEED_FR24_ENABLED", "\"${localOrEmpty("FR24_ENABLED").ifBlank { "true" }}\"")
        buildConfigField("int", "SEED_FR24_MIN_INTERVAL_MS", localOrEmpty("FR24_MIN_INTERVAL_MS").ifBlank { "180000" })
        buildConfigField("int", "SEED_OPENSKY_MIN_INTERVAL_MS", localOrEmpty("OPENSKY_MIN_INTERVAL_MS").ifBlank { "90000" })
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        // Bytecode 17. AGP 8+ still asks the toolchain service for a JDK 17
        // javac when this is VERSION_17 — Windows now has one at
        // %USERPROFILE%\.jdks\jdk-17 (auto-detect). Do not add
        // kotlin { jvmToolchain(17) }. Do not raise this to 21: WSL and CI
        // run Gradle on JDK 17 and cannot compile --release 21.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// No androidTest sources or runner: disable every AGP 8.7 device-test API
// so Studio sync does not materialize compile*AndroidTestJavaWithJavac.
androidComponents {
    beforeVariants(selector().all()) { variant ->
        variant.enableAndroidTest = false
        variant.deviceTests.values.forEach { it.enable = false }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.splashscreen)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.koin.androidx.workmanager)
    implementation(libs.retrofit)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.coil.compose)
    implementation(libs.maplibre)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
