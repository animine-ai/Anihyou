package com.axiel7.anihyou.release.core.notification

import com.axiel7.anihyou.release.core.model.Confirmation
import com.axiel7.anihyou.release.core.model.ConfirmationEvidenceKind
import com.axiel7.anihyou.release.core.model.Forecast
import com.axiel7.anihyou.release.core.model.Freshness
import com.axiel7.anihyou.release.core.model.FreshnessStatus
import com.axiel7.anihyou.release.core.model.Installment
import com.axiel7.anihyou.release.core.model.LanguageTrack
import com.axiel7.anihyou.release.core.model.MappingConfidence
import com.axiel7.anihyou.release.core.model.MappingOrigin
import com.axiel7.anihyou.release.core.model.ReleaseKind
import com.axiel7.anihyou.release.core.model.ReleaseMapping
import com.axiel7.anihyou.release.core.model.ReleaseState
import com.axiel7.anihyou.release.core.model.ReleaseStreamKey
import com.axiel7.anihyou.release.core.model.SourceIdentity
import com.axiel7.anihyou.release.core.model.SourceSeriesKey
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotificationPolicyTest {
    private val observedAt = Instant.parse("2026-09-11T12:00:00Z")

    @Test
    fun newValidConfirmationEnqueuesExactlyOneCandidate() {
        val stream = stream(LanguageTrack.DE_SUB)
        val previous = state(stream, confirmations = listOf(confirmation(stream, 4)))
        val current = state(stream, confirmations = listOf(confirmation(stream, 4), confirmation(stream, 5)))

        val decisions = ReleaseNotificationPolicy.decide(previous, current, 7L, 42, 4)

        val enqueue = decisions.single() as ReleaseNotificationDecision.Enqueue
        assertEquals(Installment.Episode(5), enqueue.candidate.identity.installment)
        assertEquals(
            "release-confirmation-v1::7::42::provider/series/EPISODE/2026/DE_SUB/episode:5::RECENT_LIST_EXPLICIT_MARKER",
            enqueue.candidate.eventKey,
        )
    }

    @Test
    fun initialStateAndRepeatedConfirmationFailClosed() {
        val stream = stream()
        val current = state(stream, confirmations = listOf(confirmation(stream, 5)))
        val initial = ReleaseNotificationPolicy.decide(null, current, 7L, 42, 0)
        val repeated = ReleaseNotificationPolicy.decide(current, current, 7L, 42, 0)

        assertEquals(
            ReleaseNotificationDecision.Suppressed(NotificationSuppressionReason.INITIAL_STATE),
            initial.single(),
        )
        assertEquals(
            ReleaseNotificationDecision.Suppressed(NotificationSuppressionReason.NO_NEW_CONFIRMATION),
            repeated.single(),
        )
    }

    @Test
    fun forecastOnlyNeverEnqueues() {
        val stream = stream()
        val previous = state(stream, forecasts = listOf(forecast(stream, 5)))
        val current = state(stream, forecasts = listOf(forecast(stream, 5)))

        val decision = ReleaseNotificationPolicy.decide(previous, current, 7L, 42, 0).single()

        assertEquals(
            ReleaseNotificationDecision.Suppressed(NotificationSuppressionReason.NO_NEW_CONFIRMATION),
            decision,
        )
    }

    @Test
    fun staleErrorAmbiguousAndUnmappedStatesNeverEnqueue() {
        val stream = stream()
        val previous = state(stream, confirmations = listOf(confirmation(stream, 4)))
        val statuses = listOf(FreshnessStatus.STALE, FreshnessStatus.ERROR)
        statuses.forEach { status ->
            val decision = ReleaseNotificationPolicy.decide(
                previous,
                state(stream, confirmations = listOf(confirmation(stream, 4), confirmation(stream, 5)), freshnessStatus = status),
                7L,
                42,
                0,
            ).single()
            assertEquals(NotificationSuppressionReason.INVALID_AUTHORITY, (decision as ReleaseNotificationDecision.Suppressed).reason)
        }
        listOf(MappingConfidence.AMBIGUOUS, MappingConfidence.NONE).forEach { confidence ->
            val decision = ReleaseNotificationPolicy.decide(
                previous,
                state(stream, confirmations = listOf(confirmation(stream, 4), confirmation(stream, 5)), confidence = confidence),
                7L,
                42,
                0,
            ).single()
            assertEquals(NotificationSuppressionReason.INVALID_AUTHORITY, (decision as ReleaseNotificationDecision.Suppressed).reason)
        }
    }

    @Test
    fun tracksAndInstallmentsHaveIndependentKeys() {
        val sub = SourceIdentity(stream(LanguageTrack.DE_SUB), Installment.Episode(5))
        val dub = SourceIdentity(stream(LanguageTrack.DE_DUB), Installment.Episode(5))
        val movie = SourceIdentity(stream(LanguageTrack.DE_SUB, ReleaseKind.MOVIE), Installment.Film(1))

        val keys = setOf(
            ReleaseNotificationPolicy.eventKey(7L, 42, sub, ConfirmationEvidenceKind.RECENT_LIST_EXPLICIT_MARKER),
            ReleaseNotificationPolicy.eventKey(7L, 42, dub, ConfirmationEvidenceKind.RECENT_LIST_EXPLICIT_MARKER),
            ReleaseNotificationPolicy.eventKey(7L, 42, movie, ConfirmationEvidenceKind.RECENT_LIST_EXPLICIT_MARKER),
        )

        assertEquals(3, keys.size)
    }


    @Test
    fun disabledAndDisappearedSourcesNeverEnqueue() {
        val stream = stream()
        val previous = state(stream, confirmations = listOf(confirmation(stream, 4)))

        val disabled = ReleaseNotificationPolicy.decide(
            previous,
            state(
                stream,
                confirmations = listOf(confirmation(stream, 4), confirmation(stream, 5)),
                freshnessStatus = FreshnessStatus.DISABLED,
            ),
            7L,
            42,
            0,
        ).single() as ReleaseNotificationDecision.Suppressed
        assertEquals(NotificationSuppressionReason.INVALID_AUTHORITY, disabled.reason)

        val disappeared = ReleaseNotificationPolicy.decide(
            previous,
            state(
                stream,
                confirmations = listOf(confirmation(stream, 4), confirmation(stream, 5)),
                sourcePresent = false,
            ),
            7L,
            42,
            0,
        ).single() as ReleaseNotificationDecision.Suppressed
        assertEquals(NotificationSuppressionReason.INVALID_AUTHORITY, disappeared.reason)
    }


    @Test
    fun nullMappingIsTreatedAsUnmapped() {
        val stream = stream()
        val previous = state(stream, confirmations = listOf(confirmation(stream, 4)))
        val current = state(
            stream,
            confirmations = listOf(confirmation(stream, 4), confirmation(stream, 5)),
            mappingOverride = null,
        )

        val decision = ReleaseNotificationPolicy.decide(previous, current, 7L, 42, 0).single()

        assertEquals(
            NotificationSuppressionReason.INVALID_AUTHORITY,
            (decision as ReleaseNotificationDecision.Suppressed).reason,
        )
    }

    @Test
    fun wrongMediaMappingAndStreamChangeAreSuppressed() {
        val stream = stream()
        val previous = state(stream, confirmations = listOf(confirmation(stream, 4)))
        val current = state(stream, confirmations = listOf(confirmation(stream, 4), confirmation(stream, 5)))

        assertEquals(
            NotificationSuppressionReason.MEDIA_MAPPING_MISMATCH,
            (ReleaseNotificationPolicy.decide(previous, current, 7L, 99, 0).single() as ReleaseNotificationDecision.Suppressed).reason,
        )
        val otherStream = stream(LanguageTrack.DE_DUB)
        assertEquals(
            NotificationSuppressionReason.STREAM_CHANGED,
            (ReleaseNotificationPolicy.decide(previous, state(otherStream), 7L, 42, 0).single() as ReleaseNotificationDecision.Suppressed).reason,
        )
    }

    private fun stream(
        track: LanguageTrack = LanguageTrack.DE_SUB,
        kind: ReleaseKind = ReleaseKind.EPISODE,
    ) = ReleaseStreamKey(
        providerId = com.axiel7.anihyou.release.core.model.ProviderId("provider"),
        stableSeriesKey = SourceSeriesKey("series"),
        releaseKind = kind,
        sourceSeason = 2026,
        languageTrack = track,
    )

    private fun state(
        stream: ReleaseStreamKey,
        confirmations: List<Confirmation> = emptyList(),
        forecasts: List<Forecast> = emptyList(),
        freshnessStatus: FreshnessStatus = FreshnessStatus.FRESH,
        confidence: MappingConfidence = MappingConfidence.HIGH,
        sourcePresent: Boolean = true,
        mappingOverride: ReleaseMapping? = ReleaseMapping(
            mediaId = 42,
            confidence = confidence,
            score = 0.98,
            runnerUpMargin = 0.20,
            evidence = "policy-test",
            matcherVersion = "test-v1",
            origin = MappingOrigin.AUTO,
        ),
    ) = ReleaseState(
        stream = stream,
        confirmations = confirmations,
        forecasts = forecasts,
        freshness = Freshness(
            status = freshnessStatus,
            lastAttemptAt = observedAt,
            lastSuccessAt = observedAt,
            observedAt = observedAt,
            parserVersion = "wp06-test",
            sourceHash = "hash",
        ),
        mapping = mappingOverride,

        sourcePresent = sourcePresent,
        revision = 1L,
    )

    private fun confirmation(stream: ReleaseStreamKey, episode: Int) = Confirmation(
        identity = SourceIdentity(stream, Installment.Episode(episode)),
        evidenceKind = ConfirmationEvidenceKind.RECENT_LIST_EXPLICIT_MARKER,
        confirmedObservedAt = observedAt,
    )

    private fun forecast(stream: ReleaseStreamKey, episode: Int) = Forecast(
        identity = SourceIdentity(stream, Installment.Episode(episode)),
        forecastAt = observedAt.plusSeconds(86_400L),
        sourceDate = LocalDate.of(2026, 9, 12),
        sourceTime = "20:00",
        sourceZone = ZoneId.of("UTC"),
        approximate = false,
        observedAt = observedAt,
    )
}