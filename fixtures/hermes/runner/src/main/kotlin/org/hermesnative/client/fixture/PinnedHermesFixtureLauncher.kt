package org.hermesnative.client.fixture

/** Resolves the descriptor action to the repository-owned deterministic Gateway fixture. */
internal object PinnedHermesFixtureLauncher {
    private const val BUILD_AND_RUN_PINNED_HERMES = "build-and-run-pinned-hermes"

    fun start(descriptor: PinnedFixtureDescriptor): GatewayProcess {
        require(descriptor.value("startup.command") == BUILD_AND_RUN_PINNED_HERMES) {
            "Unsupported deterministic fixture startup action."
        }
        require(descriptor.value("startup.mode") == "ephemeral") {
            "Deterministic fixture startup must be ephemeral."
        }
        require(descriptor.value("startup.network") == "isolated") {
            "Deterministic fixture startup must use an isolated network."
        }
        require(descriptor.value("startup.supervisor") == "disabled") {
            "Deterministic fixture startup must disable restart supervision."
        }
        return LocalSyntheticGatewayProcess.start(descriptor)
    }
}
