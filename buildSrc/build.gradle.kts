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

tasks.test {
    useJUnit()
    maxParallelForks = 1
    systemProperty("architecture.projectDir", projectDir.parentFile.absolutePath)
}
