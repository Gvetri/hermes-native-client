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
    testImplementation(kotlin("test"))
    testImplementation(libs.junit4)
}

tasks.test {
    useJUnit()
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
}
