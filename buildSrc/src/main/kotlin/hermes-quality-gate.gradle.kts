// The convention plugin for the modified production modules. It declares the Kover verification and
// the PIT run once, so a module build script cannot drift from the declared quality policy: the
// plugin reads every threshold from `QualityPolicy` and fails on a module that has no declared scope.
import info.solidsoft.gradle.pitest.PitestTask
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit
import org.hermesnative.client.buildlogic.QualityPolicy

plugins {
    id("org.jetbrains.kotlinx.kover")
    id("info.solidsoft.pitest")
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

tasks.withType<Test>().configureEach {
    // The fixture-driven tests resolve their JSON and SSE fixtures from the repository root, so
    // every test task in a modified module gets the same root property.
    systemProperty("fixture.repositoryRoot", rootProject.projectDir.absolutePath)
}

tasks.named<PitestTask>("pitest") {
    targetClasses.set(listOf("${mutationScope.targetPackage}.*"))
    threads.set(2)
    mutationThreshold.set(mutationScope.minMutationScorePercent)
    testStrengthThreshold.set(mutationScope.minTestStrengthPercent)
    timestampedReports.set(false)
    outputFormats.set(setOf("XML", "HTML"))
    // The mutation run starts its own test minion, which must receive the same fixture property as
    // the `test` task, or the fixture-driven tests fail inside the mutation run only.
    childProcessJvmArgs.set(listOf("-Dfixture.repositoryRoot=${rootProject.projectDir.absolutePath}"))
}
