package org.hermesnative.client.feature.entry.domain

/**
 * Failure-detail boundary for Run failures.
 *
 * The client never renders server-provided failure text. The supported Gateway
 * contract does not define a structured safe diagnostic field, so raw
 * `run_result` content cannot be proven safe to display: bearer credentials,
 * authorization headers, cookies, stack traces, prompts, responses, and other
 * sensitive server content could otherwise pass through. The failure surface
 * therefore shows only client-authored safe messages, and an optional
 * expandable technical detail is available only for content the client
 * constructed itself.
 */
fun isRunRetryEligible(
    state: RunPresentationState?,
    originalUserMessage: String?,
): Boolean = state == RunPresentationState.FAILED && !originalUserMessage.isNullOrBlank()
