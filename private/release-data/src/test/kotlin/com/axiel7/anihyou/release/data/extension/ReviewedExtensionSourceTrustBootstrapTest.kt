package com.axiel7.anihyou.release.data.extension

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReviewedExtensionSourceTrustBootstrapTest {
    private val configuration = ProductionExtensionHostConfiguration(
        repositoryId = "test-repository", initialRootSha256 = "a".repeat(64),
        distributionOrigins = setOf("https://packages.example.org"), allowedHosts = setOf("data.example.org"),
        approvedAuthority = emptySet(),
    )
    @Test fun absentInputsRemainUnavailableForEveryUrl() = runBlocking {
        val bootstrap = reviewedSourceBootstrap(null)
        assertFalse(bootstrap.provisioned)
        assertNull(bootstrap.authenticate(NormalizedExtensionSource.parse("https://packages.example.org/repo")))
    }
    @Test fun provisionedPublicPinIsIndependentOfThePastedUrlAndRejectsOtherOrigins() = runBlocking {
        val bootstrap = reviewedSourceBootstrap(configuration)
        val anchor = bootstrap.authenticate(NormalizedExtensionSource.parse("https://packages.example.org/repo"))!!
        assertEquals(configuration.repositoryId, anchor.pin.repositoryId)
        assertEquals(configuration.initialRootSha256, anchor.pin.initialRootSha256)
        assertEquals(configuration.allowedHosts, anchor.allowedHosts)
        assertNull(bootstrap.authenticate(NormalizedExtensionSource.parse("https://attacker.example.org/repo")))
        assertNull(bootstrap.authenticate(NormalizedExtensionSource.parse("https://packages.example.org.evil.org/repo")))
    }
    @Test fun aWrongRootFingerprintStillFailsTheExistingVerifier() = runBlocking {
        val anchor = reviewedSourceBootstrap(configuration)
            .authenticate(NormalizedExtensionSource.parse("https://packages.example.org/repo"))!!
        assertTrue(runCatching { ExtensionTrustVerifier(anchor.pin).root("{}".toByteArray(), null,
            java.time.Instant.parse("2026-10-04T12:00:00Z")) }.isFailure)
    }
}
