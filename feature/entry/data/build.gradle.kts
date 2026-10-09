plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    id("hermes-quality-gate")
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
}
