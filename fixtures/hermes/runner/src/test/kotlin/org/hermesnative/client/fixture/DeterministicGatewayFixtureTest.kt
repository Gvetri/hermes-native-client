package org.hermesnative.client.fixture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DeterministicGatewayFixtureTest {
    private val repositoryRoot =
        File(
            requireNotNull(System.getProperty("fixture.repositoryRoot")) {
                "fixture.repositoryRoot must identify the repository root"
            },
        )

    @Test
    fun executes_a_synthetic_gateway_test_and_tears_down_the_fixture() {
        val fixture = DeterministicGatewayFixture.fromDescriptor(descriptorFile())

        val result =
            fixture.execute { context ->
                assertEquals("127.0.0.1", context.endpoint.host)
                assertEquals("d9833c5615b80e199a174cd67d90ab430695a972", context.provenanceValue)
                assertTrue(context.syntheticState.snapshot().isEmpty())

                context.syntheticState.put("test-message", "synthetic-response")

                assertEquals("synthetic-response", context.syntheticState.get("test-message"))
                "passed"
            }

        assertEquals("passed", result)
        assertEquals(FixtureLifecycleState.TORN_DOWN, fixture.lifecycleState)
        assertTrue(fixture.lastSyntheticState.isEmpty())
    }

    @Test
    fun reads_the_capability_expected_status_from_the_pinned_descriptor() {
        val descriptor = PinnedFixtureDescriptor.load(descriptorFile())

        assertEquals("200", descriptor.value("capability_check.expected_status"))
    }

    @Test
    fun resolves_the_pinned_provenance_identity_for_fixture_construction() {
        val descriptor = PinnedFixtureDescriptor.load(descriptorFile())

        assertEquals(descriptor.provenanceField, descriptor.provenance.field)
        assertEquals(descriptor.provenanceValue, descriptor.provenance.value)
    }

    @Test
    fun exposes_explicit_setup_readiness_test_and_teardown_hooks() {
        val fixture = DeterministicGatewayFixture.fromDescriptor(descriptorFile())

        fixture.setup()
        assertEquals(FixtureLifecycleState.STARTED, fixture.lifecycleState)

        fixture.awaitReady()
        assertEquals(FixtureLifecycleState.READY, fixture.lifecycleState)

        fixture.runTest { context ->
            assertEquals(fixture.endpoint, context.endpoint)
        }
        assertEquals(FixtureLifecycleState.READY, fixture.lifecycleState)

        fixture.teardown()
        assertEquals(FixtureLifecycleState.TORN_DOWN, fixture.lifecycleState)
    }

    @Test
    fun resets_synthetic_state_before_each_test_and_during_teardown() {
        val fixture = DeterministicGatewayFixture.fromDescriptor(descriptorFile())
        fixture.setup()
        fixture.awaitReady()

        fixture.runTest { context ->
            context.syntheticState.put("first-test", "synthetic-only")
        }
        assertTrue(fixture.lastSyntheticState.isEmpty())

        fixture.runTest { context ->
            assertTrue(context.syntheticState.snapshot().isEmpty())
            context.syntheticState.put("second-test", "synthetic-only")
        }
        fixture.teardown()

        assertTrue(fixture.lastSyntheticState.isEmpty())
    }

    @Test
    fun resolves_the_pinned_startup_action_to_the_local_fixture() {
        val descriptor = PinnedFixtureDescriptor.load(descriptorFile())
        val process = PinnedHermesFixtureLauncher.start(descriptor)

        try {
            assertEquals(descriptor.provenanceValue, process.provenanceValue)
            assertEquals("127.0.0.1", process.endpoint.host)
            assertTrue(process.isRunning)
        } finally {
            process.stop()
        }
    }

    @Test
    fun startup_failure_fails_the_invoking_fixture_job() {
        val fixture =
            DeterministicGatewayFixture(
                descriptorFile = descriptorFile(),
                processFactory =
                    GatewayProcessFactory {
                        throw IllegalStateException("simulated startup failure")
                    },
            )

        val error =
            assertThrows(FixtureStartupException::class.java) {
                fixture.execute { _ -> error("The test must not run after startup failure.") }
            }

        assertTrue(error.message.orEmpty().contains("startup failed"))
        assertEquals(FixtureLifecycleState.TORN_DOWN, fixture.lifecycleState)
    }

    @Test
    fun health_failure_fails_the_invoking_fixture_job() {
        val fixture = fixtureWithBehavior(SyntheticGatewayBehavior(healthStatus = 503))

        val error =
            assertThrows(FixtureReadinessException::class.java) {
                fixture.execute { _ -> error("The test must not run after health failure.") }
            }

        assertTrue(error.message.orEmpty().contains("readiness failed"))
        assertEquals(FixtureLifecycleState.TORN_DOWN, fixture.lifecycleState)
    }

    @Test
    fun capability_failure_fails_the_invoking_fixture_job() {
        val fixture = fixtureWithBehavior(SyntheticGatewayBehavior(capabilities = emptySet()))

        val error =
            assertThrows(FixtureReadinessException::class.java) {
                fixture.execute { _ -> error("The test must not run after capability failure.") }
            }

        assertTrue(error.message.orEmpty().contains("required capability"))
        assertEquals(FixtureLifecycleState.TORN_DOWN, fixture.lifecycleState)
    }

    @Test
    fun cleanup_failure_is_distinct_after_a_successful_test() {
        val fixture = fixtureWithStopFailure()

        val error =
            assertThrows(FixtureCleanupException::class.java) {
                fixture.execute { _ -> "passed" }
            }

        assertTrue(error.message.orEmpty().contains("cleanup failed"))
        assertEquals(FixtureLifecycleState.TORN_DOWN, fixture.lifecycleState)
    }

    @Test
    fun teardown_runs_after_a_failed_test_and_preserves_cleanup_failure() {
        val fixture = fixtureWithStopFailure()
        val testFailure = IllegalStateException("simulated test failure")

        val error =
            assertThrows(IllegalStateException::class.java) {
                fixture.execute {
                    it.syntheticState.put("failure-case", "synthetic-only")
                    throw testFailure
                }
            }

        assertSame(testFailure, error)
        assertTrue(error.suppressed.any { it is FixtureCleanupException })
        assertEquals(FixtureLifecycleState.TORN_DOWN, fixture.lifecycleState)
        assertTrue(fixture.lastSyntheticState.isEmpty())
    }

    @Test
    fun teardown_reports_state_reset_failure_when_state_remains() {
        val fixture = fixtureWithResetFailure()
        fixture.setup()
        fixture.awaitReady()

        var state: SyntheticTestState? = null
        fixture.runTest { context -> state = context.syntheticState }
        state!!.put("leaked-state", "synthetic-only")

        val error =
            assertThrows(FixtureCleanupException::class.java) {
                fixture.teardown()
            }

        assertTrue(error.message.orEmpty().contains("cleanup failed"))
        assertEquals(FixtureLifecycleState.TORN_DOWN, fixture.lifecycleState)
        assertEquals("synthetic-only", fixture.lastSyntheticState["leaked-state"])
    }

    private fun fixtureWithBehavior(behavior: SyntheticGatewayBehavior): DeterministicGatewayFixture =
        DeterministicGatewayFixture(
            descriptorFile = descriptorFile(),
            processFactory =
                GatewayProcessFactory { descriptor ->
                    LocalSyntheticGatewayProcess.start(
                        descriptor = descriptor,
                        pinnedProvenance = descriptor.provenance,
                        behavior = behavior,
                    )
                },
        )

    private fun fixtureWithStopFailure(): DeterministicGatewayFixture =
        DeterministicGatewayFixture(
            descriptorFile = descriptorFile(),
            processFactory =
                GatewayProcessFactory { descriptor ->
                    val delegate =
                        LocalSyntheticGatewayProcess.start(
                            descriptor = descriptor,
                            pinnedProvenance = descriptor.provenance,
                        )
                    object : GatewayProcess {
                        override val endpoint = delegate.endpoint
                        override val provenanceValue = delegate.provenanceValue
                        override val isRunning: Boolean
                            get() = delegate.isRunning

                        override fun stop() {
                            delegate.stop()
                            throw IllegalStateException("simulated cleanup failure")
                        }
                    }
                },
        )

    private fun fixtureWithResetFailure(): DeterministicGatewayFixture {
        var resetCount = 0
        return DeterministicGatewayFixture(
            descriptorFile = descriptorFile(),
            processFactory =
                GatewayProcessFactory { descriptor ->
                    LocalSyntheticGatewayProcess.start(
                        descriptor = descriptor,
                        pinnedProvenance = descriptor.provenance,
                    )
                },
            syntheticState =
                SyntheticTestState { values ->
                    if (resetCount++ < 2) {
                        values.clear()
                    }
                },
        )
    }

    private fun descriptorFile(): File = repositoryRoot.resolve("fixtures/hermes/pinned-fixture.properties")
}
