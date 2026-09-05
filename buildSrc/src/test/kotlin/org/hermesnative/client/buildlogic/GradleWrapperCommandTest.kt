package org.hermesnative.client.buildlogic

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class GradleWrapperCommandTest {
    @Test
    fun posix_uses_posix_wrapper() {
        val projectDir = File("project with spaces").absoluteFile

        assertEquals(
            listOf(projectDir.resolve("gradlew").absolutePath),
            gradleWrapperCommand(projectDir, "Linux"),
        )
    }

    @Test
    fun windows_uses_command_processor_for_batch_wrapper() {
        val projectDir = File("project with spaces").absoluteFile

        assertEquals(
            listOf("cmd.exe", "/c", "call", projectDir.resolve("gradlew.bat").absolutePath),
            gradleWrapperCommand(projectDir, "Windows 11"),
        )
    }
}