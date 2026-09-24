package com.axiel7.anihyou.release.data.malsync

import com.axiel7.anihyou.release.core.model.AniWorldMappingSubject
import com.axiel7.anihyou.release.core.model.ExternalMappingCandidate
import com.axiel7.anihyou.release.core.model.ExternalProvider
import com.axiel7.anihyou.release.core.model.MappingConfidence
import com.axiel7.anihyou.release.core.model.MappingSource

enum class MALSyncCapabilityStatus {
    UNSUPPORTED,
    AVAILABLE,
}

data class MALSyncCapabilityAssessment(
    val status: MALSyncCapabilityStatus,
    val inspectedAdapterRevision: String,
    val inspectedDocumentationRevision: String,
    val diagnostic: String,
) {
    init {
        require(inspectedAdapterRevision.isNotBlank())
        require(inspectedDocumentationRevision.isNotBlank())
        require(diagnostic.isNotBlank())
    }
}

/**
 * Capability is explicit because MALSync's current AniWorld adapter has no
 * database property and the published support matrix marks database support
 * as unavailable. No endpoint is guessed from the page name.
 */
object MALSyncCapabilities {
    const val CURRENT_ADAPTER_REVISION = "e5f9f9c2168991b01bea68133d42e87f4647391b"
    const val CURRENT_ADAPTER_BLOB = "654b0441c98b346de316416501118404f9b32617"
    const val CURRENT_DOCUMENTATION_BLOB = "27571d81e656d4499af64c44fc52fdabdb3e012b"

    val ANIWORLD: MALSyncCapabilityAssessment = MALSyncCapabilityAssessment(
        status = MALSyncCapabilityStatus.UNSUPPORTED,
        inspectedAdapterRevision = "$CURRENT_ADAPTER_REVISION:$CURRENT_ADAPTER_BLOB",
        inspectedDocumentationRevision = CURRENT_DOCUMENTATION_BLOB,
        diagnostic = "AniWorld has no MALSync database capability; mapping API is unsupported",
    )
}

sealed interface MALSyncMappingLookup {
    data class Exact(
        val externalProvider: ExternalProvider,
        val externalId: String,
        val provenance: String,
    ) : MALSyncMappingLookup {
        init {
            require(externalId.isNotBlank()) { "MALSync external id must not be blank" }
            require(provenance.isNotBlank()) { "MALSync provenance must not be blank" }
        }
    }

    data object NotFound : MALSyncMappingLookup

    data class Ambiguous(
        val candidates: List<ExternalMappingCandidate>,
        val diagnostic: String,
    ) : MALSyncMappingLookup {
        init {
            require(candidates.size > 1) { "MALSync ambiguity needs multiple candidates" }
            require(diagnostic.isNotBlank())
        }
    }

    data class Failure(val diagnostic: String) : MALSyncMappingLookup {
        init { require(diagnostic.isNotBlank()) }
    }

    data class Unsupported(val diagnostic: String) : MALSyncMappingLookup {
        init { require(diagnostic.isNotBlank()) }
    }
}

/** Provider-neutral mapping client. It has no release evidence surface. */
fun interface MALSyncMappingClient {
    suspend fun lookup(identifier: MALSyncIdentifier): MALSyncMappingLookup
}

/** Explicit no-network implementation used while AniWorld capability is absent. */
class UnsupportedMALSyncMappingClient(
    private val assessment: MALSyncCapabilityAssessment = MALSyncCapabilities.ANIWORLD,
) : MALSyncMappingClient {
    override suspend fun lookup(identifier: MALSyncIdentifier): MALSyncMappingLookup =
        MALSyncMappingLookup.Unsupported(assessment.diagnostic)
}

internal fun MALSyncMappingLookup.Exact.toCandidate(subject: AniWorldMappingSubject): ExternalMappingCandidate =
    ExternalMappingCandidate(
        subject = subject,
        externalProvider = externalProvider,
        externalId = externalId,
        mappingSource = MappingSource.MALSYNC,
        confidence = MappingConfidence.EXACT,
    )
