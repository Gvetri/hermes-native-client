package org.hermesnative.client.buildlogic

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityGateConfigurationTest {
    private val repositoryRoot =
        File(
            requireNotNull(System.getProperty("architecture.projectDir")) {
                "architecture.projectDir must identify the repository root"
            },
        )

    @Test
    fun gradle_wrapper_declares_official_distribution_checksum() {
        val checksum =
            repositoryRoot.resolve("gradle/wrapper/gradle-wrapper.properties").readLines()
                .firstOrNull { it.startsWith("distributionSha256Sum=") }
                ?.substringAfter('=')

        assertEquals(
            "6f74b601422d6d6fc4e1f9a1ab6522f642c2fdcbc15ae33ebd30ba3d7198e854",
            checksum,
        )
    }

    @Test
    fun emulator_job_enables_kvm_before_runner() {
        val workflow = repositoryRoot.resolve(".github/workflows/quality-gate.yml").readText()
        val kvmSetup =
            listOf(
                "      - name: Enable KVM group perms",
                "        run: |",
                "          echo 'KERNEL=\"kvm\", GROUP=\"kvm\", MODE=\"0666\", OPTIONS+=\"static_node=kvm\"' | sudo tee /etc/udev/rules.d/99-kvm4all.rules",
                "          sudo udevadm control --reload-rules",
                "          sudo udevadm trigger --name-match=kvm",
            ).joinToString("\n")

        assertTrue("The workflow must use GitHub-hosted Ubuntu for the emulator job.", workflow.contains("runs-on: ubuntu-latest"))
        assertTrue("The emulator job must use the documented KVM setup.", workflow.contains(kvmSetup))
        assertTrue(
            "KVM must be enabled before the emulator action.",
            workflow.indexOf("Enable KVM group perms") <
                workflow.indexOf("reactivecircus/android-emulator-runner@v2"),
        )
    }

    @Test
    fun checkout_steps_disable_persisted_credentials() {
        val lines = repositoryRoot.resolve(".github/workflows/quality-gate.yml").readLines()
        val checkoutStepIndices = lines.indices.filter { index ->
            lines[index].trim() == "- uses: actions/checkout@v4"
        }

        assertEquals("The workflow must keep all seven checkout steps explicit.", 7, checkoutStepIndices.size)
        checkoutStepIndices.forEach { index ->
            val stepEnd =
                (index + 1 until lines.size).firstOrNull { nextIndex ->
                    lines[nextIndex].startsWith("      - ")
                } ?: lines.size
            assertTrue(
                "Checkout step at line ${index + 1} does not disable credential persistence.",
                lines.subList(index, stepEnd).any { it.trim() == "persist-credentials: false" },
            )
        }
    }
}