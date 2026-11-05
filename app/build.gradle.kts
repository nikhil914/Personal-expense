import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Ledgerly Android app. Only included in the build when an Android SDK is available
// (see root settings.gradle.kts). Build instructions and plugin-classpath notes: app/README.md.
plugins {
    // Versions come from the root buildscript classpath (see root build.gradle.kts).
    id("com.android.application")
    // No version: the Kotlin Gradle plugin (2.0.21) is already on the root build classpath because the
    // root build.gradle.kts declares kotlin("jvm") version "2.0.21" apply false.
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "app.ledgerly"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.ledgerly"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    flavorDimensions += "capture"
    productFlavors {
        create("full") {
            dimension = "capture"
            // SMS + notification capture. Requires the Play SMS Permissions Declaration
            // (money-management exception) before publishing.
            buildConfigField("boolean", "SMS_CAPTURE_ENABLED", "true")
        }
        create("notifOnly") {
            dimension = "capture"
            // Play-rejection fallback: notification listener only, no SMS permissions in the manifest.
            applicationIdSuffix = ".notif"
            versionNameSuffix = "-notif"
            buildConfigField("boolean", "SMS_CAPTURE_ENABLED", "false")
        }
    }

    // Release signing: real keystore from env (CI secrets). Without it, release builds fall back to the
    // debug key so they still install for testing, but such APKs must NOT be uploaded to Play.
    val releaseKeystore = System.getenv("LEDGERLY_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("LEDGERLY_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("LEDGERLY_KEY_ALIAS")
                keyPassword = System.getenv("LEDGERLY_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/LICENSE*")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    // Shared pure-Kotlin engine modules. Only :core:model is referenced from app code today; the
    // others are wired through adapter interfaces in app/src/main/java/app/ledgerly/engine/EngineContracts.kt.
    implementation(project(":core:model"))
    implementation(project(":core:parser"))
    implementation(project(":core:fx"))
    implementation(project(":core:dedup"))
    implementation(project(":core:audit"))
    implementation(project(":core:budget"))
    implementation(project(":core:insights"))
    implementation(project(":core:engine"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)

    implementation(libs.work.runtime.ktx)
    implementation(libs.datastore.preferences)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    implementation(libs.biometric)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
