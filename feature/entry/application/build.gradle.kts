plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktlint)
    id("hermes-quality-gate")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":feature:entry:domain"))
    testImplementation(kotlin("test"))
    testImplementation(libs.junit4)
}

tasks.test {
    useJUnit()
}
