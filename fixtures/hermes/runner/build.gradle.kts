import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit4)
}

tasks.test {
    useJUnit()
    systemProperty("fixture.repositoryRoot", rootProject.projectDir.absolutePath)
}

fun Test.configureFocusedFixtureTest(testClass: String) {
    group = "verification"
    dependsOn("testClasses")
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnit()
    filter {
        includeTestsMatching(testClass)
    }
    systemProperty("fixture.repositoryRoot", rootProject.projectDir.absolutePath)
}

tasks.register<Test>("fixtureLifecycleTest") {
    configureFocusedFixtureTest("org.hermesnative.client.fixture.DeterministicGatewayFixtureTest")
}

tasks.register<Test>("fixtureContractTest") {
    configureFocusedFixtureTest("org.hermesnative.client.fixture.contract.ContractFixtureParserTest")
}
