package com.axiel7.anihyou.release.core.state

import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PostponementBindingPolicyTest {
    private val observed = Instant.parse("2026-09-26T12:00:00Z")
    private val corrected = Instant.parse("2026-09-28T18:00:00Z")

    @Test
    fun titleLikeRowsWithoutProviderLinkStayUnboundEvenWithOneLocalCandidate() {
        val result = PostponementBindingPolicy.bind(proof(
            linkedSeriesPath = null,
            installment = Installment.Episode(3),
            sourceSeason = 2,
        ))
        assertEquals(PostponementBindingResult.Unbound(PostponementBindingResult.Reason.IDENTITY_MISSING), result)
    }

    @Test
    fun directExistenceDoesNotReplaceSupportRecordIdentityLink() {
        val result = PostponementBindingPolicy.bind(proof(
            linkedSeriesPath = null,
            installment = Installment.Episode(3),
            sourceSeason = 2,
        ))
        assertTrue(result is PostponementBindingResult.Unbound)
    }

    @Test
    fun explicitSeasonZeroAndDubCanBindWithoutRenumbering() {
        val result = PostponementBindingPolicy.bind(proof(
            installment = Installment.Episode(1),
            sourceSeason = 0,
            tracks = setOf(LanguageTrack.DE_DUB),
        ))
        assertTrue(result is PostponementBindingResult.Bound)
        assertTrue((result as PostponementBindingResult.Bound).canonicalKey.contains("DE_DUB"))
    }

    @Test
    fun episodeZeroRemainsAValidExactInstallment() {
        assertTrue(PostponementBindingPolicy.bind(proof(
            installment = Installment.Episode(0), sourceSeason = 2,
        )) is PostponementBindingResult.Bound)
    }

    @Test
    fun filmZeroAndUnnumberedSpecialRemainNonBindable() {
        val film = PostponementBindingPolicy.bind(proof(
            installment = Installment.Film(0), sourceSeason = null, filmNamespace = true,
        ))
        val special = PostponementBindingPolicy.bind(proof(
            installment = Installment.Special(1), sourceSeason = null, filmNamespace = true,
        ))
        assertEquals(PostponementBindingResult.Unbound(PostponementBindingResult.Reason.INVALID_FILM), film)
        assertEquals(PostponementBindingResult.Unbound(PostponementBindingResult.Reason.INVALID_FILM), special)
    }

    @Test
    fun missingTrackCannotBorrowAUserPreference() {
        val result = PostponementBindingPolicy.bind(proof(
            installment = Installment.Episode(3), sourceSeason = 2, tracks = emptySet(),
        ))
        assertEquals(PostponementBindingResult.Unbound(PostponementBindingResult.Reason.TRACK_MISSING), result)
    }

    @Test
    fun conflictingSubAndDubStatementIsAmbiguous() {
        val result = PostponementBindingPolicy.bind(proof(
            installment = Installment.Episode(3), sourceSeason = 2,
            tracks = setOf(LanguageTrack.DE_SUB, LanguageTrack.DE_DUB),
        ))
        assertEquals(PostponementBindingResult.Ambiguous(PostponementBindingResult.Reason.AMBIGUOUS_TRACK), result)
    }

    @Test
    fun conflictingTargetAndSeasonBecomesAmbiguous() {
        val base = proof(installment = Installment.Episode(3), sourceSeason = 2)
        val seriesConflict = PostponementBindingPolicy.bind(base.copy(
            statementSeriesPath = "/anime/stream/other-series",
        ))
        val seasonConflict = PostponementBindingPolicy.bind(base.copy(statementSourceSeason = 3))
        assertEquals(PostponementBindingResult.Ambiguous(PostponementBindingResult.Reason.EVIDENCE_CONFLICT),
            seriesConflict)
        assertEquals(PostponementBindingResult.Ambiguous(PostponementBindingResult.Reason.EVIDENCE_CONFLICT),
            seasonConflict)
    }

    @Test
    fun missingCorrectionTimeCannotBecomeDelayed() {
        val result = PostponementBindingPolicy.bind(proof(
            installment = Installment.Episode(3), sourceSeason = 2, correctionAt = null,
        ))
        assertEquals(PostponementBindingResult.Unbound(PostponementBindingResult.Reason.SEMANTICS_MISSING), result)
    }

    @Test
    fun changedSnapshotOrUnprovenRecordOrdinalFailsClosed() {
        val wrongHash = PostponementBindingPolicy.bind(proof(
            installment = Installment.Episode(3), sourceSeason = 2,
        ).copy(sourceHash = "not-a-hash"))
        val badOrdinal = PostponementBindingPolicy.bind(proof(
            installment = Installment.Episode(3), sourceSeason = 2,
        ).copy(recordOrdinal = 512))
        assertEquals(PostponementBindingResult.Unbound(PostponementBindingResult.Reason.SOURCE_PROOF_MISSING),
            wrongHash)
        assertEquals(PostponementBindingResult.Unbound(PostponementBindingResult.Reason.SOURCE_PROOF_MISSING),
            badOrdinal)
    }

    private fun proof(
        linkedSeriesPath: String? = "/anime/stream/example-series",
        installment: Installment,
        sourceSeason: Int?,
        tracks: Set<LanguageTrack> = setOf(LanguageTrack.DE_SUB),
        correctionAt: Instant? = corrected,
        filmNamespace: Boolean = false,
    ) = PostponementBindingProof(
        sourceUrl = "https://aniworld.to/support/frage/anime-verschiebungen",
        sourceHash = "a".repeat(64),
        parserVersion = "support-v1",
        recordOrdinal = 7,
        linkedSeriesPath = linkedSeriesPath,
        explicitSourceSeason = sourceSeason,
        installment = installment,
        tracks = tracks,
        correctionAt = correctionAt,
        providerRecordLink = true,
        providerStatement = true,
        observedAt = observed,
        statementSeriesPath = linkedSeriesPath,
        statementSourceSeason = sourceSeason,
        statementInstallment = installment,
        statementTracks = tracks,
        filmNamespace = filmNamespace,
    )
}
