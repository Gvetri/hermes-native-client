import info.solidsoft.gradle.pitest.PitestTask
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import org.hermesnative.client.buildlogic.QualityPolicy

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.kover)
    alias(libs.plugins.pitest)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":feature:entry:domain"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit4)
    testImplementation(project(":feature:entry:application"))
    testImplementation(project(":fixtures:hermes:runner"))
}

tasks.test {
    useJUnit()
    systemProperty("fixture.repositoryRoot", rootProject.projectDir.absolutePath)
}

val coverageScope = QualityPolicy.coverageFor(project.path)
val mutationScope = QualityPolicy.mutationFor(project.path)

kover {
    reports {
        verify {
            rule {
                bound {
                    coverageUnits = CoverageUnit.LINE
                    minValue = coverageScope.minLineCoveragePercent
                }
                bound {
                    coverageUnits = CoverageUnit.BRANCH
                    minValue = coverageScope.minBranchCoveragePercent
                }
            }
        }
    }
}

tasks.named<PitestTask>("pitest") {
    targetClasses.set(listOf("${mutationScope.targetPackage}.*"))
    threads.set(2)
    mutationThreshold.set(mutationScope.minMutationScorePercent)
    testStrengthThreshold.set(mutationScope.minTestStrengthPercent)
    timestampedReports.set(false)
    outputFormats.set(setOf("XML", "HTML"))
    // The fixture-driven data tests resolve their JSON and SSE fixtures from the repository root,
    // so the mutation run must give the test minion the same property as the `test` task.
    childProcessJvmArgs.set(listOf("-Dfixture.repositoryRoot=${rootProject.projectDir.absolutePath}"))
}
