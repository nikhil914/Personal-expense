val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // Google Maven only needed for the Android app module.
        if (System.getenv("ANDROID_HOME") != null || System.getenv("ANDROID_SDK_ROOT") != null) google()
    }
}
rootProject.name = "ledgerly"

// Pure-Kotlin JVM modules: build and test anywhere (no Android SDK needed).
include(
    ":core:model",
    ":core:parser",
    ":core:fx",
    ":core:dedup",
    ":core:audit",
    ":core:budget",
    ":core:insights",
)

// Android app module: only included when an Android SDK is available.
if (hasAndroidSdk) include(":app")
