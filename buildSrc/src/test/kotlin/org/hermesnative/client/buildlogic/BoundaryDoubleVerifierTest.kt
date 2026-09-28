package org.hermesnative.client.buildlogic

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class BoundaryDoubleVerifierTest {
    private val requirement = BoundaryDoubleRequirement(boundaryType = "org.example.FixtureBoundary", kind = "repository")

    @Test
    fun accepts_a_declared_boundary_with_a_deterministic_double() {
        val repository =
            repository(
                mainSource = "interface FixtureBoundary {\n    fun load(): String\n}\n",
                testSource = "class FakeFixtureBoundary : FixtureBoundary {\n    override fun load(): String = \"fixture\"\n}\n",
            )

        val report = verify(repository)

        assertEquals(
            "Boundary double verification checked 1 double(s) for 1 declared datasource and repository " +
                "boundary(ies), with 0 declared allowance(s).",
            report,
        )
    }

    @Test
    fun rejects_a_boundary_without_a_production_declaration() {
        val repository =
            repository(
                mainSource = "interface OtherBoundary {\n    fun load(): String\n}\n",
                testSource = "class FakeFixtureBoundary : FixtureBoundary {\n    override fun load(): String = \"fixture\"\n}\n",
            )
        assertVerificationFails("org.example.FixtureBoundary has no production declaration") {
            verify(repository)
        }
    }

    @Test
    fun rejects_a_boundary_without_a_test_double() {
        val repository =
            repository(
                mainSource = "interface FixtureBoundary {\n    fun load(): String\n}\n",
                testSource = "class UnrelatedFixture {\n    fun load(): String = \"fixture\"\n}\n",
            )
        assertVerificationFails("org.example.FixtureBoundary has no in-repository test double") {
            verify(repository)
        }
    }

    @Test
    fun rejects_a_double_that_reads_the_wall_clock() {
        val repository =
            repository(
                mainSource = "interface FixtureBoundary {\n    fun load(): String\n}\n",
                testSource =
                    "class FakeFixtureBoundary : FixtureBoundary {\n" +
                        "    override fun load(): String = System.currentTimeMillis().toString()\n}\n",
            )
        assertVerificationFails("FakeFixtureBoundary implements org.example.FixtureBoundary with non-deterministic System.currentTimeMillis") {
            verify(repository)
        }
    }

    @Test
    fun rejects_a_double_that_reads_randomness() {
        val repository =
            repository(
                mainSource = "interface FixtureBoundary {\n    fun load(): String\n}\n",
                testSource =
                    "class FakeFixtureBoundary : FixtureBoundary {\n" +
                        "    private val seed = Random(7)\n" +
                        "    override fun load(): String = seed.toString()\n}\n",
            )
        assertVerificationFails("FakeFixtureBoundary implements org.example.FixtureBoundary with non-deterministic Random") {
            verify(repository)
        }
    }

    @Test
    fun accepts_a_declared_allowance_for_a_double_reference() {
        val repository =
            repository(
                mainSource = "interface FixtureBoundary {\n    fun load(): String\n}\n",
                testSource =
                    "class FakeFixtureBoundary : FixtureBoundary {\n" +
                        "    override fun load(): String = System.currentTimeMillis().toString()\n}\n",
            )

        verify(
            repository,
            allowances =
                listOf(
                    DoubleReferenceAllowance(
                        doubleType = "FakeFixtureBoundary",
                        reference = "System.currentTimeMillis",
                        reason = "fixture allowance",
                    ),
                ),
        )
    }

    @Test
    fun rejects_a_stale_allowance() {
        val repository =
            repository(
                mainSource = "interface FixtureBoundary {\n    fun load(): String\n}\n",
                testSource = "class FakeFixtureBoundary : FixtureBoundary {\n    override fun load(): String = \"fixture\"\n}\n",
            )
        assertVerificationFails("declared allowance for FakeFixtureBoundary / System.currentTimeMillis is stale") {
            verify(
                repository,
                allowances =
                    listOf(
                        DoubleReferenceAllowance(
                            doubleType = "FakeFixtureBoundary",
                            reference = "System.currentTimeMillis",
                            reason = "fixture allowance",
                        ),
                    ),
            )
        }
    }

    @Test
    fun rejects_a_verification_without_declared_boundaries() {
        assertVerificationFails("has no declared datasource or repository boundary") {
            BoundaryDoubleVerifier.verify(
                repositoryRoot = repository(
                    mainSource = "interface FixtureBoundary {\n    fun load(): String\n}\n",
                    testSource = "class FakeFixtureBoundary : FixtureBoundary {\n    override fun load(): String = \"fixture\"\n}\n",
                ),
                requirements = emptyList(),
                forbiddenReferences = listOf("Random"),
                allowedReferences = emptyList(),
                moduleRoots = listOf("feature"),
            )
        }
    }

    @Test
    fun rejects_a_verification_without_sources_to_scan() {
        val repository = Files.createTempDirectory("boundary-double").toFile()
        assertVerificationFails("found no main Kotlin sources") {
            BoundaryDoubleVerifier.verify(
                repositoryRoot = repository,
                requirements = listOf(requirement),
                forbiddenReferences = listOf("Random"),
                allowedReferences = emptyList(),
                moduleRoots = listOf("feature"),
            )
        }
    }

    private fun verify(
        repository: File,
        allowances: List<DoubleReferenceAllowance> = emptyList(),
    ): String =
        BoundaryDoubleVerifier.verify(
            repositoryRoot = repository,
            requirements = listOf(requirement),
            forbiddenReferences = listOf("Random", "System.currentTimeMillis"),
            allowedReferences = allowances,
            moduleRoots = listOf("feature"),
        )

    private fun repository(mainSource: String, testSource: String): File {
        val repository = Files.createTempDirectory("boundary-double").toFile()
        writeSource(repository, "main", mainSource)
        writeSource(repository, "test", testSource)
        return repository
    }

    private fun writeSource(repository: File, sourceSet: String, source: String) {
        val sourceFile =
            repository.resolve("feature/fixture/src/$sourceSet/kotlin/org/example/FixtureSource.kt")
        sourceFile.parentFile.mkdirs()
        sourceFile.writeText(source)
    }
}
