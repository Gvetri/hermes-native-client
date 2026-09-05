import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "org.hermesnative.client"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.hermesnative.client"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        ndk {
            abiFilters += setOf("arm64-v8a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(project(":feature:entry:presentation"))
    implementation(project(":feature:entry:wiring"))

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit4)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

tasks.register("verifyConnectedAndroidTests") {
    group = "verification"
    description = "Fails when app instrumentation tests are missing, skipped, or unsuccessful."
    dependsOn("connectedDebugAndroidTest")
    doLast {
        val resultDirectory =
            layout.buildDirectory
                .dir("outputs/androidTest-results/connected/debug")
                .get()
                .asFile
        check(resultDirectory.isDirectory) {
            "Missing Android test result directory: ${resultDirectory.relativeTo(projectDir)}"
        }
        val reports = resultDirectory.walkTopDown().filter { it.extension == "xml" }.toList()
        check(reports.isNotEmpty()) { "No Android test result XML files were produced." }

        var tests = 0
        var skipped = 0
        var failures = 0
        var errors = 0
        reports.forEach { report ->
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(report)
            for (index in 0 until document.getElementsByTagName("testsuite").length) {
                val suite = document.getElementsByTagName("testsuite").item(index) as Element
                tests += suite.getAttribute("tests").toIntOrNull() ?: 0
                skipped += suite.getAttribute("skipped").toIntOrNull() ?: 0
                failures += suite.getAttribute("failures").toIntOrNull() ?: 0
                errors += suite.getAttribute("errors").toIntOrNull() ?: 0
            }
        }
        check(tests > 0) { "Android instrumentation produced zero executed tests." }
        check(skipped == 0) { "Android instrumentation skipped $skipped test(s)." }
        check(failures == 0) { "Android instrumentation reported $failures failure(s)." }
        check(errors == 0) { "Android instrumentation reported $errors error(s)." }
    }
}
