import org.hermesnative.client.buildlogic.FixtureDescriptorValidator
import org.hermesnative.client.buildlogic.gradleWrapperCommand
import org.gradle.kotlin.dsl.register
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.ktlint) apply false
}

subprojects {
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_21)
    }
}

val architectureSourceRoots = listOf(
    file("feature/entry/domain/src/main/kotlin"),
    file("feature/entry/application/src/main/kotlin"),
)

val architectureModuleBuildFiles = listOf(
    file("feature/entry/domain/build.gradle.kts"),
    file("feature/entry/application/build.gradle.kts"),
)

val architectureRuleTestSource =
    file("buildSrc/src/test/kotlin/org/hermesnative/client/buildlogic/ArchitectureCheckTest.kt")
val architectureRuleTestClass = "org.hermesnative.client.buildlogic.ArchitectureCheckTest"
val architectureRuleTestBuildDir = file("build/architecture-rule-tests/buildSrc")
val architectureRuleTestResults = architectureRuleTestBuildDir.resolve("test-results/test")
var architectureRuleTestsExecuted = false
val fixtureDescriptorFile = file("fixtures/hermes/pinned-fixture.properties")
val fixtureDescriptorTestSource =
    file("buildSrc/src/test/kotlin/org/hermesnative/client/buildlogic/FixtureDescriptorValidatorTest.kt")
val fixtureDescriptorTestClass = "org.hermesnative.client.buildlogic.FixtureDescriptorValidatorTest"
val fixtureDescriptorTestBuildDir = file("build/fixture-descriptor-tests/buildSrc")
val fixtureDescriptorTestResults = fixtureDescriptorTestBuildDir.resolve("test-results/test")

val forbiddenQualifiedPackages = listOf(
    "android",
    "androidx",
    "com.google.gson",
    "com.squareup.moshi",
    "dagger",
    "hilt",
    "io.ktor",
    "jakarta.inject",
    "javax.inject",
    "kotlinx.serialization",
    "okhttp3",
    "org.json",
    "org.koin",
    "retrofit2",
    "java.net",
    "org.hermesnative.client.feature.entry.data",
    "org.hermesnative.client.feature.entry.presentation",
    "org.hermesnative.client.feature.entry.wiring",
)

val forbiddenQualifiedReference = Regex(
    "(?<![A-Za-z0-9_])(?:${forbiddenQualifiedPackages.joinToString(separator = "|", transform = Regex::escape)})\\.[A-Za-z_][A-Za-z0-9_.]*",
)

val forbiddenDependencyTokens = listOf(
    "android.",
    "androidx",
    "androidx.",
    "com.android.",
    "compose",
    "com.google.gson",
    "com.squareup.moshi",
    "dagger",
    "hilt",
    "io.ktor",
    "gson",
    "jakarta.inject",
    "javax.inject",
    "kotlinx.serialization",
    "serialization",
    "ktor",
    "moshi",
    "okhttp3",
    "okhttp",
    "org.json",
    "org.koin",
    "koin",
    "retrofit2",
    "retrofit",
    "java.net",
    "project(\":feature:entry:data\")",
    "project(\":feature:entry:presentation\")",
    "project(\":feature:entry:wiring\")",
)

val forbiddenNamedProjectDependency = Regex(
    """project\s*\([^)]*\bpath\s*=\s*["']:(?:feature:entry:(?:data|presentation|wiring))["'][^)]*\)""",
)

fun requireArchitectureRuleTestSource() {
    check(architectureRuleTestSource.isFile && architectureRuleTestSource.readText().isNotBlank()) {
        "Architecture rule tests found no focused architecture test source."
    }
}

fun requireFocusedTestEvidence(
    testSource: File,
    testResults: File,
    testClass: String,
    label: String,
) {
    check(testSource.isFile && testSource.readText().isNotBlank()) {
        "$label found no focused test source."
    }
    check(testResults.isDirectory) {
        "$label found no test result directory."
    }
    val reports = testResults.walkTopDown()
        .filter { it.isFile && it.extension == "xml" }
        .toList()
    check(reports.isNotEmpty()) {
        "$label found no test result reports."
    }
    check(reports.all { it.length() > 0 }) {
        "$label found an empty test result report."
    }

    val documentBuilderFactory = DocumentBuilderFactory.newInstance()
    val focusedReports = reports.mapNotNull { report ->
        val document = documentBuilderFactory.newDocumentBuilder().parse(report)
        document.takeIf { it.documentElement.getAttribute("name") == testClass }
    }
    check(focusedReports.isNotEmpty()) {
        "$label found no focused test result report."
    }
    check(focusedReports.all { document ->
        document.getElementsByTagName("failure").length == 0 &&
            document.getElementsByTagName("error").length == 0
    }) {
        "$label found failed focused test evidence."
    }
    val executedTests = focusedReports.sumOf { document ->
        val testCases = document.getElementsByTagName("testcase")
        (0 until testCases.length).count { index ->
            val testCase = testCases.item(index) as org.w3c.dom.Element
            testCase.getElementsByTagName("skipped").length == 0
        }
    }
    check(executedTests > 0) {
        "$label found no executed test evidence."
    }
}

fun requireArchitectureRuleTestEvidence() {
    requireArchitectureRuleTestSource()
    requireFocusedTestEvidence(
        testSource = architectureRuleTestSource,
        testResults = architectureRuleTestResults,
        testClass = architectureRuleTestClass,
        label = "Architecture rule tests",
    )
}

fun requireFixtureDescriptorTestEvidence() {
    requireFocusedTestEvidence(
        testSource = fixtureDescriptorTestSource,
        testResults = fixtureDescriptorTestResults,
        testClass = fixtureDescriptorTestClass,
        label = "Fixture descriptor tests",
    )
}

tasks.register("architectureCheck") {
    group = "verification"
    description = "Checks the inward dependency rules for domain and application code."
    doLast {
        val sourceFiles = architectureSourceRoots.flatMap { root ->
            if (root.isDirectory) root.walkTopDown().filter { it.extension == "kt" }.toList() else emptyList()
        }
        check(sourceFiles.isNotEmpty()) { "Architecture check found no domain/application Kotlin sources." }
        check(architectureModuleBuildFiles.all { it.isFile }) {
            "Architecture check found a missing domain/application build script."
        }

        val sourceViolations = sourceFiles.flatMap { sourceFile ->
            sourceFile.readLines().mapIndexedNotNull { index, line ->
                if (forbiddenQualifiedReference.containsMatchIn(line.replace("`", ""))) {
                    "${sourceFile.relativeTo(projectDir)}:${index + 1}: forbidden qualified dependency: $line"
                } else {
                    null
                }
            }
        }
        val dependencyViolations = architectureModuleBuildFiles.flatMap { buildFile ->
            val content = buildFile.readText()
            val tokenViolations = content.lines().mapIndexedNotNull { index, line ->
                if (forbiddenDependencyTokens.any(line::contains)) {
                    "${buildFile.relativeTo(projectDir)}:${index + 1}: forbidden module dependency: $line"
                } else {
                    null
                }
            }
            val namedProjectViolations = forbiddenNamedProjectDependency.findAll(content).map { match ->
                val lineNumber = content.substring(0, match.range.first).count { it == '\n' } + 1
                "${buildFile.relativeTo(projectDir)}:$lineNumber: forbidden named project dependency: ${match.value}"
            }
            tokenViolations + namedProjectViolations
        }
        val violations = sourceViolations + dependencyViolations
        check(violations.isEmpty()) { "Architecture violations:\n${violations.joinToString("\n")}" }
    }
}

tasks.register("architectureRuleTests") {
    group = "verification"
    description = "Runs focused failure tests for architecture enforcement."
    dependsOn("architectureCheck")
    doLast {
        requireArchitectureRuleTestSource()
        architectureRuleTestBuildDir.deleteRecursively()
        val result =
            ProcessBuilder(
                *(gradleWrapperCommand(projectDir) +
                    listOf(
                        "-p",
                        "buildSrc",
                        "-Parchitecture.testBuildDir=${architectureRuleTestBuildDir.absolutePath}",
                        "test",
                        "--tests",
                        architectureRuleTestClass,
                        "--rerun-tasks",
                        "--no-build-cache",
                        "--no-daemon",
                        "--console=plain",
                    )).toTypedArray(),
            ).directory(projectDir)
                .inheritIO()
                .start()
                .waitFor()
        check(result == 0) { "Architecture rule tests failed with exit code $result." }
        requireArchitectureRuleTestEvidence()
        architectureRuleTestsExecuted = true
    }
}

tasks.register("verifyNoMocks") {
    group = "verification"
    description = "Fails when mock frameworks or mock-based test doubles are present."
    doLast {
        val sourceFiles = fileTree(projectDir) {
            include("**/src/main/**/*.kt")
            include("**/src/test/**/*.kt")
            include("**/src/androidTest/**/*.kt")
        }
        val forbiddenMockPatterns = listOf(
            Regex("(?i)mockito"),
            Regex("(?i)mockk"),
            Regex("(?i)mockwebserver"),
            Regex("(?i)\\bmock\\s*\\("),
        )
        val violations = sourceFiles.flatMap { sourceFile ->
            sourceFile.readLines().mapIndexedNotNull { index, line ->
                if (forbiddenMockPatterns.any { it.containsMatchIn(line) }) {
                    "${sourceFile.relativeTo(projectDir)}:${index + 1}: mock-based test code is not allowed"
                } else {
                    null
                }
            }
        }
        check(violations.isEmpty()) { violations.joinToString("\n") }
    }
}

tasks.register("formatCheck") {
    group = "verification"
    description = "Runs the repository formatter for every Kotlin module."
    dependsOn(subprojects.map { it.tasks.matching { task -> task.name == "ktlintCheck" } })
}

val requiredUnitTestTasks = listOf(
    ":feature:entry:application:test",
    ":feature:entry:data:test",
    ":feature:entry:presentation:testDebugUnitTest",
)

tasks.register("verifyRequiredUnitTests") {
    group = "verification"
    description = "Fails when a declared unit-test scope is empty or did not produce results."
    dependsOn(requiredUnitTestTasks)
    doLast {
        val resultDirectories = listOf(
            file("feature/entry/application/build/test-results/test"),
            file("feature/entry/data/build/test-results/test"),
            file("feature/entry/presentation/build/test-results/testDebugUnitTest"),
        )
        val failures = resultDirectories.flatMap { resultDirectory ->
            if (!resultDirectory.isDirectory) {
                listOf("missing test result directory: ${resultDirectory.relativeTo(projectDir)}")
            } else {
                val reports = resultDirectory.walkTopDown().filter { it.extension == "xml" }.toList()
                if (reports.isEmpty()) {
                    listOf("empty test result directory: ${resultDirectory.relativeTo(projectDir)}")
                } else {
                    val executedTests = reports.sumOf { report ->
                        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(report)
                        document.documentElement.getAttribute("tests").toIntOrNull() ?: 0
                    }
                    if (executedTests == 0) {
                        listOf("zero executed tests: ${resultDirectory.relativeTo(projectDir)}")
                    } else {
                        emptyList()
                    }
                }
            }
        }
        check(failures.isEmpty()) { failures.joinToString("\n") }
    }
}

tasks.register("fixtureDescriptorTests") {
    group = "verification"
    description = "Runs focused tests for the deterministic Hermes fixture descriptor."
    doLast {
        check(fixtureDescriptorTestSource.isFile) {
            "Fixture descriptor test source is missing."
        }
        fixtureDescriptorTestBuildDir.deleteRecursively()
        val result =
            ProcessBuilder(
                *(gradleWrapperCommand(projectDir) +
                    listOf(
                        "-p",
                        "buildSrc",
                        "-PfixtureDescriptor.testBuildDir=${fixtureDescriptorTestBuildDir.absolutePath}",
                        "test",
                        "--tests",
                        fixtureDescriptorTestClass,
                        "--rerun-tasks",
                        "--no-build-cache",
                        "--no-daemon",
                        "--console=plain",
                    )).toTypedArray(),
            ).directory(projectDir)
                .inheritIO()
                .start()
                .waitFor()
        check(result == 0) { "Fixture descriptor tests failed with exit code $result." }
        requireFixtureDescriptorTestEvidence()
    }
}

tasks.register("fixtureLifecycleTests") {
    group = "verification"
    description = "Runs deterministic local Gateway fixture lifecycle tests without provider access."
    dependsOn(":fixtures:hermes:runner:test")
    doLast {
        val lifecycleTests = project(":fixtures:hermes:runner").tasks.named("test").get()
        check(lifecycleTests.state.didWork) {
            "fixtureLifecycleTests requires the fixture runner tests to execute in this invocation."
        }
    }
}

tasks.register("verifyFixtureDescriptor") {
    group = "verification"
    description = "Validates the immutable Hermes fixture provenance and lifecycle contract."
    inputs.file(fixtureDescriptorFile)
    doLast {
        FixtureDescriptorValidator.validate(fixtureDescriptorFile)
    }
}

tasks.register("qualityGate") {
    group = "verification"
    description = "Runs all deterministic local quality checks for the initial public project."
    dependsOn(
        "formatCheck",
        "architectureCheck",
        "architectureRuleTests",
        "verifyNoMocks",
        "fixtureDescriptorTests",
        "fixtureLifecycleTests",
        "verifyFixtureDescriptor",
        "verifyRequiredUnitTests",
        ":app:lintDebug",
        ":app:assembleDebug",
        ":app:assembleRelease",
    )
    doLast {
        check(architectureRuleTestsExecuted) {
            "qualityGate requires architectureRuleTests to execute in this invocation."
        }
        requireArchitectureRuleTestEvidence()
    }
}
