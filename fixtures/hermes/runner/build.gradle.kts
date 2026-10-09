import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
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
    // The scenario test reads the checked-in journey fixtures from disk, so
    // they are declared as inputs; otherwise a scenario-only edit could be
    // reported UP-TO-DATE and the changed scenario would never be parsed.
    inputs.dir(rootProject.file("fixtures/hermes/journey"))
        .withPropertyName("journeyFixtures")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(rootProject.file("fixtures/hermes/journey-tls"))
        .withPropertyName("journeyTls")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootProject.file("fixtures/hermes/pinned-fixture.properties"))
        .withPropertyName("pinnedDescriptor")
        .withPathSensitivity(PathSensitivity.RELATIVE)
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
    val keystorePath =
        providers.gradleProperty("journeyKeystore")
            .orElse(rootProject.file("fixtures/hermes/journey-tls/journey-gateway.p12").absolutePath)
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("org.hermesnative.client.fixture.journey.JourneyVerifierKt")
    doFirst {
        args(telemetryUrl.get(), scenarioName.get(), keystorePath.get())
    }
}

tasks.register("journeyGatewayClasspath") {
    group = "verification"
    description = "Writes the journey Gateway runtime classpath so CI can launch the JVM directly."
    dependsOn("classes")
    doLast {
        val classpathFile = File(layout.buildDirectory.get().asFile, "journey-classpath.txt")
        classpathFile.parentFile.mkdirs()
        classpathFile.writeText(sourceSets["main"].runtimeClasspath.asPath)
    }
}
