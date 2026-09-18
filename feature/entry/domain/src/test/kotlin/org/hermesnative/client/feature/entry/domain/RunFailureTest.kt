package org.hermesnative.client.feature.entry.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunFailureTest {
    @Test
    fun retry_requires_a_confirmed_terminal_failure_and_the_original_message() {
        assertTrue(isRunRetryEligible(RunPresentationState.FAILED, "Original request"))
        assertFalse(isRunRetryEligible(RunPresentationState.FAILED, null))
        assertFalse(isRunRetryEligible(RunPresentationState.FAILED, "  "))
        assertFalse(isRunRetryEligible(RunPresentationState.SUCCEEDED, "Original request"))
        assertFalse(isRunRetryEligible(RunPresentationState.CANCELLED, "Original request"))
        assertFalse(isRunRetryEligible(RunPresentationState.UNCERTAIN, "Original request"))
        assertFalse(isRunRetryEligible(RunPresentationState.RUNNING, "Original request"))
        assertFalse(isRunRetryEligible(null, "Original request"))
    }
}
