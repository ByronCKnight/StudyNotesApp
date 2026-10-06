import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing is read from keystore.properties (git-ignored). Without it, release builds fall
// back to the debug key so the project still builds on any machine.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

// App version. The Release workflow passes the tag it builds (`-PappVersion=1.2.3`); local builds
// use the default. versionCode is derived (1.2.3 -> 1002003, same formula as UpdatePolicy.versionCode)
// so every release installs over the previous one.
val appVersion = (project.findProperty("appVersion") as String?)?.removePrefix("v")?.trim()?.ifEmpty { null } ?: "1.0.0"
val appVersionParts = appVersion.split(".").map { part ->
    part.toIntOrNull() ?: error("appVersion must be major.minor.patch, got '$appVersion'")
}
require(appVersionParts.size == 3) { "appVersion must be major.minor.patch, got '$appVersion'" }
val appVersionCode = appVersionParts[0] * 1_000_000 + appVersionParts[1] * 1_000 + appVersionParts[2]

// In-app updates check this repository's GitHub releases. The repository is private, so a read-only
// token (`-PupdateToken=...` or STUDYNOTES_UPDATE_TOKEN, stamped in by the Release workflow) is
// needed; a build without one does no update checks (local debug builds).
val updateRepo = "ByronCKnight/StudyNotesApp"
val updateToken = (project.findProperty("updateToken") as String?) ?: System.getenv("STUDYNOTES_UPDATE_TOKEN") ?: ""
require(updateToken.none { it == '"' || it == '\\' }) { "updateToken contains characters that can't be stamped into BuildConfig" }

android {
    namespace = "com.studynotes.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.studynotes.app"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
        buildConfigField("String", "UPDATE_TOKEN", "\"$updateToken\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
    packaging {
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES")
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    lint {
        // A call above minSdk without a version check crashes on older devices; fail the release build.
        fatal += "NewApi"
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.pdfbox.android)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.kxml2)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
