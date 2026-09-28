plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    // The convention plugin in this source set applies both plugins, so they must be on the build
    // logic classpath. The versions come from the main build's version catalog.
    implementation(libs.kover.gradle.plugin)
    implementation(libs.gradle.pitest.plugin)
    testImplementation("junit:junit:4.13.2")
}

providers.gradleProperty("architecture.testBuildDir").orNull?.let { testBuildDir ->
    layout.buildDirectory.set(file(testBuildDir))
}

providers.gradleProperty("fixtureDescriptor.testBuildDir").orNull?.let { testBuildDir ->
    layout.buildDirectory.set(file(testBuildDir))
}

tasks.test {
    useJUnit()
    maxParallelForks = 1
    systemProperty("architecture.projectDir", projectDir.parentFile.absolutePath)
}
