import org.gradle.api.tasks.testing.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.ktlint)
}

@OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)
roborazzi {
    outputDir.set(layout.projectDirectory.dir("src/test/snapshots"))
    compare {
        outputDir.set(layout.buildDirectory.dir("outputs/roborazzi-comparison"))
    }
}

val roborazziBaselineDirectory = layout.projectDirectory.dir("src/test/snapshots")
val roborazziBaselineManifest = layout.projectDirectory.file("src/test/roborazzi-baselines.txt")

tasks.register("verifyRoborazziBaselineManifest") {
    group = "verification"
    description = "Verifies that the selected Roborazzi baseline set has no missing or unexpected PNGs."
    inputs.dir(roborazziBaselineDirectory)
    inputs.file(roborazziBaselineManifest)
    doLast {
        val manifestEntries =
            roborazziBaselineManifest.asFile.readLines()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("#") }
        val expectedBaselines = manifestEntries.toSet()
        check(expectedBaselines.size == manifestEntries.size) {
            "Roborazzi baseline manifest contains duplicate entries."
        }
        check(expectedBaselines.all { it.endsWith(".png") && !it.startsWith("/") && !it.contains("..") }) {
            "Roborazzi baseline manifest must contain only relative PNG paths."
        }

        val actualBaselines =
            roborazziBaselineDirectory.asFile.walkTopDown()
                .filter { it.isFile && it.extension == "png" }
                .map { it.relativeTo(roborazziBaselineDirectory.asFile).invariantSeparatorsPath }
                .toSet()
        val missingBaselines = expectedBaselines - actualBaselines
        val unexpectedBaselines = actualBaselines - expectedBaselines
        check(missingBaselines.isEmpty() && unexpectedBaselines.isEmpty()) {
            buildString {
                appendLine("Roborazzi baseline manifest mismatch.")
                if (missingBaselines.isNotEmpty()) {
                    appendLine("Missing baselines:")
                    missingBaselines.sorted().forEach { appendLine("- $it") }
                }
                if (unexpectedBaselines.isNotEmpty()) {
                    appendLine("Unexpected baselines:")
                    unexpectedBaselines.sorted().forEach { appendLine("- $it") }
                }
            }
        }
        logger.lifecycle("Roborazzi baseline manifest verified: ${actualBaselines.size} PNG baseline(s).")
    }
}

tasks.configureEach {
    if (name == "verifyRoborazziDebug") {
        dependsOn("verifyRoborazziBaselineManifest")
    }
}

android {
    namespace = "org.hermesnative.client.feature.entry.presentation"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.core)
    implementation(project(":feature:entry:application"))
    implementation(project(":feature:entry:domain"))

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit4)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.okhttp)
    testImplementation(project(":feature:entry:data"))
    testImplementation(project(":fixtures:hermes:runner"))

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

tasks.withType<Test>().configureEach {
    systemProperty("fixture.repositoryRoot", rootProject.projectDir.absolutePath)
}

tasks.register("verifyConnectedAndroidTests") {
    group = "verification"
    description = "Fails when presentation instrumentation tests are missing, skipped, or unsuccessful."
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
