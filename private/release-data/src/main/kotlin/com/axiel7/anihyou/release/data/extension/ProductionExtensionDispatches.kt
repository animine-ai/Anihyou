package com.axiel7.anihyou.release.data.extension

import com.axiel7.anihyou.release.core.extension.ExtensionId
import com.axiel7.anihyou.release.core.extension.ExtensionRuntime
import java.io.File

/** Compose both capabilities over one host-owned network policy and reservation ledger. */
class ProductionExtensionDispatches private constructor(
    val release: ExtensionHostCoordinator,
    val navigation: ProductionNavigationDispatcher,
) {
    companion object {
        fun create(
            repository: VerifiedExtensionRepository,
            runtime: ExtensionRuntime,
            appPrivateDirectory: File,
            observationPolicy: ExtensionObservationPolicy,
            parseFuelByExtensionId: Map<ExtensionId, Long> = emptyMap(),
        ): ProductionExtensionDispatches {
            val transport = ProductionExtensionTransportFactory.create(appPrivateDirectory)
            return ProductionExtensionDispatches(
                ExtensionHostCoordinator(repository, runtime, transport, observationPolicy, enabled = { true },
                    parseFuelByExtensionId = parseFuelByExtensionId),
                ProductionNavigationDispatcher(repository, runtime, transport),
            )
        }
    }
}
