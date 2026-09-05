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

val sourceRoots = listOf(
    file("feature/entry/domain/src/main/kotlin"),
    file("feature/entry/application/src/main/kotlin"),
)

val forbiddenDomainImports = listOf(
    "import android.",
    "import androidx.",
    "import androidx.compose.",
    "import com.google.gson.",
    "import com.squareup.moshi.",
    "import dagger.",
    "import io.ktor.",
    "import javax.inject.",
    "import kotlinx.serialization.",
    "import okhttp3.",
    "import org.json.",
    "import retrofit2.",
    "import java.net.",
    "import java.net.http.",
    "import org.hermesnative.client.feature.entry.data.",
    "import org.hermesnative.client.feature.entry.presentation.",
    "import org.hermesnative.client.feature.entry.wiring.",
)

tasks.register("architectureCheck") {
    group = "verification"
    description = "Checks the inward dependency rules for domain and application code."
    doLast {
        val sourceFiles = sourceRoots.flatMap { root ->
            if (root.isDirectory) root.walkTopDown().filter { it.extension == "kt" }.toList() else emptyList()
        }
        check(sourceFiles.isNotEmpty()) { "Architecture check found no domain/application Kotlin sources." }

        val violations = sourceFiles.flatMap { sourceFile ->
            sourceFile.readLines().mapIndexedNotNull { index, line ->
                if (forbiddenDomainImports.any(line::contains)) {
                    "${sourceFile.relativeTo(projectDir)}:${index + 1}: forbidden dependency: $line"
                } else {
                    null
                }
            }
        }
        check(violations.isEmpty()) { "Architecture violations:\n${violations.joinToString("\n")}" }
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

tasks.register("qualityGate") {
    group = "verification"
    description = "Runs all deterministic local quality checks for the initial public project."
    dependsOn(
        "formatCheck",
        "architectureCheck",
        "verifyNoMocks",
        "verifyRequiredUnitTests",
        ":app:lintDebug",
        ":app:assembleDebug",
        ":app:assembleRelease",
    )
}
