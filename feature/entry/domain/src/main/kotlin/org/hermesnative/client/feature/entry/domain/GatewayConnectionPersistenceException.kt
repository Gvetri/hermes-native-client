package org.hermesnative.client.feature.entry.domain

/** A local connection record could not be replaced or removed safely. */
class GatewayConnectionPersistenceException(
    cause: Throwable? = null,
) : RuntimeException("Could not save the Gateway credential securely. Try again.", cause)
