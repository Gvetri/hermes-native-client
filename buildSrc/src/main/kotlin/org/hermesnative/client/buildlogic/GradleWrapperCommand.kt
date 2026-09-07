package org.hermesnative.client.buildlogic

import java.io.File

fun gradleWrapperCommand(
    projectDir: File,
    osName: String = System.getProperty("os.name"),
): List<String> =
    if (osName.startsWith("Windows", ignoreCase = true)) {
        listOf("cmd.exe", "/c", "call", projectDir.resolve("gradlew.bat").absolutePath)
    } else {
        listOf(projectDir.resolve("gradlew").absolutePath)
    }