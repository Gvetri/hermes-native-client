plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "org.hermesnative.client.feature.entry.wiring"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":feature:entry:application"))
    implementation(project(":feature:entry:data"))
    implementation(project(":feature:entry:domain"))
    implementation(project(":feature:entry:presentation"))
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
}
