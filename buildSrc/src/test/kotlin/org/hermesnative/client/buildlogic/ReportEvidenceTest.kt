package org.hermesnative.client.buildlogic

import org.junit.Test

class ReportEvidenceTest {
    @Test
    fun accepts_an_invocation_that_contains_every_required_task() {
        requireTasksInInvocation(
            label = "Coverage verification",
            requiredTasks = listOf(":feature:entry:domain:test", ":feature:entry:application:test"),
            invocationTasks =
                setOf(
                    ":feature:entry:domain:test",
                    ":feature:entry:application:test",
                    ":coverageVerify",
                ),
        )
    }

    @Test
    fun rejects_an_invocation_that_excluded_a_required_task() {
        assertVerificationFails("Coverage verification requires [:feature:entry:data:test]") {
            requireTasksInInvocation(
                label = "Coverage verification",
                requiredTasks = listOf(":feature:entry:domain:test", ":feature:entry:data:test"),
                invocationTasks = setOf(":feature:entry:domain:test"),
            )
        }
    }
}
