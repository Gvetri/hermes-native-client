import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":feature:entry:domain"))
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

tasks.register<Test>("journeyScenarioTest") {
    configureFocusedFixtureTest("org.hermesnative.client.fixture.journey.JourneyScenarioTest")
}

tasks.register<JavaExec>("runJourneyGateway") {
    group = "verification"
    description = "Starts one deterministic journey Gateway from an explicit repository-owned scenario."
    val scenarioName = providers.gradleProperty("scenario").orElse("")
    val keystorePath = providers.gradleProperty("journeyKeystore")
    val keystorePassword = providers.gradleProperty("journeyKeystorePassword")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.hermesnative.client.fixture.journey.JourneyGatewayMainKt")
    systemProperty("fixture.repositoryRoot", rootProject.projectDir.absolutePath)
    args(rootProject.file("fixtures/hermes/journey/scenarios/${scenarioName.get()}.json").absolutePath)
    keystorePath.orNull?.let { args(it) }
    keystorePassword.orNull?.let { args(it) }
}

tasks.register<JavaExec>("runJourneyVerifier") {
    group = "verification"
    description = "Asserts repository-owned gateway invariants for one completed journey."
    val telemetryUrl = providers.gradleProperty("telemetryUrl")
    val scenarioName = providers.gradleProperty("scenario")
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.hermesnative.client.fixture.journey.JourneyVerifierKt")
    doFirst {
        args(telemetryUrl.get(), scenarioName.get())
    }
}

tasks.register("journeyGatewayClasspath") {
    group = "verification"
    description = "Writes the journey Gateway runtime classpath so CI can launch the JVM directly."
    doLast {
        File(layout.buildDirectory.get().asFile, "journey-classpath.txt")
            .writeText(sourceSets["main"].runtimeClasspath.asPath)
    }
}
