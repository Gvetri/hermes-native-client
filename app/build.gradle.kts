import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

val nightlyVersionCode =
    providers.gradleProperty("nightlyVersionCode").orNull?.let { value ->
        require(value.matches(Regex("[1-9][0-9]*"))) { "Invalid generated Nightly version code." }
        requireNotNull(value.toIntOrNull()) { "Nightly version code is out of range." }.also { code ->
            require(code in 2..2100000000) { "Nightly version code is out of range." }
        }
    }
val nightlySourceSha = providers.gradleProperty("nightlySourceSha").orNull
require((nightlyVersionCode == null) == (nightlySourceSha == null)) {
    "Nightly builds require both a generated version code and an exact source commit."
}
require(nightlySourceSha == null || nightlySourceSha.matches(Regex("[0-9a-f]{40}"))) {
    "Nightly builds require a full source commit SHA."
}

android {
    namespace = "org.hermesnative.client"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.hermesnative.client"
        minSdk = 24
        targetSdk = 35
        versionCode = nightlyVersionCode ?: 1
        versionName = nightlySourceSha?.let { "nightly-$nightlyVersionCode-${it.take(12)}" } ?: "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        ndk {
            abiFilters += if (nightlyVersionCode == null) setOf("arm64-v8a", "x86_64") else setOf("arm64-v8a")
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
    androidTestImplementation(project(":feature:entry:domain"))
    // The activity-launching tests clear the saved Gateway connection through the production
    // datasource, so its interface module must be on the androidTest compile classpath.
    androidTestImplementation(project(":feature:entry:data"))
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    // The debug-only shell host activity builds a connected UI state directly, so the
    // on-device configuration tests can render a connected shell without a live Gateway.
    debugImplementation(project(":feature:entry:domain"))
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
