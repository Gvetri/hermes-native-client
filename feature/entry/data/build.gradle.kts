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
    implementation(libs.okhttp)
    testImplementation(kotlin("test"))
    testImplementation(libs.junit4)
}

tasks.test {
    useJUnit()
    systemProperty("fixture.repositoryRoot", rootProject.projectDir.absolutePath)
}
