plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

providers.gradleProperty("architecture.testBuildDir").orNull?.let { testBuildDir ->
    layout.buildDirectory.set(file(testBuildDir))
}

tasks.test {
    useJUnit()
    maxParallelForks = 1
    systemProperty("architecture.projectDir", projectDir.parentFile.absolutePath)
}
