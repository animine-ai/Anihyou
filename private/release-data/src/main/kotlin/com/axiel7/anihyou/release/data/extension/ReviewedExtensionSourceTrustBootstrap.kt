package com.axiel7.anihyou.release.data.extension


/**
 * The caller provisions independently reviewed public pins. A pasted URL supplies neither the pin nor any Authority.
 * The origin allowlist only admits metadata transport; root identity/digest, threshold signatures, delegations,
 * index sequence/expiry/revocations and archive signatures remain mandatory in ExtensionInstallStore.
 */
internal fun reviewedSourceBootstrap(
    configuration: ProductionExtensionHostConfiguration?,
): ExtensionSourceTrustBootstrap {
    if (configuration == null) return UnavailableExtensionSourceTrustBootstrap
    val pin = AppTrustPin(configuration.repositoryId, configuration.initialRootSha256,
        configuration.distributionOrigins)
    require(configuration.allowedHosts.isNotEmpty())
    val anchor = AuthenticatedExtensionSourceAnchor(pin, configuration.allowedHosts.toSet())
    return ExtensionSourceTrustBootstrap { source: NormalizedExtensionSource ->
        anchor.takeIf { source.origin in pin.distributionOrigins }
    }
}
