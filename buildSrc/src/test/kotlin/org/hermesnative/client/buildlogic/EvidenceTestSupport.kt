package org.hermesnative.client.buildlogic

import org.junit.Assert.assertTrue

/** One synthetic class entry of a Kover report fixture. */
internal data class FixtureClass(
    val name: String,
    val lineCovered: Int,
    val lineMissed: Int,
    val branchCovered: Int,
    val branchMissed: Int,
)

/** Requires the verification to fail closed with a message naming the rule that was broken. */
internal fun assertVerificationFails(expected: String, block: () -> Unit) {
    val failure = runCatching(block).exceptionOrNull()
    val message = failure?.message.orEmpty()
    assertTrue(
        "Expected a verification failure containing '$expected', but the result was: ${failure ?: "no failure"}",
        failure is IllegalStateException && message.contains(expected),
    )
}
