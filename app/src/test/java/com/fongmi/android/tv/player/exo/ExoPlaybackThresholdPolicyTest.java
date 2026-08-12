package com.fongmi.android.tv.player.exo;

import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.PlaybackRoute;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExoPlaybackThresholdPolicyTest {

    private static final long NOW = 100_000L;
    private static final PlaybackAutoContext.SessionToken SESSION =
            new PlaybackAutoContext.SessionToken("p-a1-1", 1);

    @Test
    public void unknownFactsKeepExistingConservativeValues() {
        ExoPlaybackThresholdPolicy.Decision decision = resolve(inputs(
                resource(
                        PlaybackAutoContext.Protocol.UNKNOWN,
                        PlaybackAutoContext.StreamKind.UNKNOWN,
                        PlaybackAutoContext.TransferUnit.UNKNOWN,
                        null),
                PlaybackAutoContext.PathFacts.unknown(),
                ExoThroughputEstimator.Snapshot.empty(),
                ForwardBufferTrend.Snapshot.unknown(),
                1_500,
                3_000,
                0,
                PlaybackAutoContext.Confidence.UNKNOWN,
                0,
                0,
                false,
                -1,
                0,
                -1));

        assertEquals(1_500, decision.startBufferMs());
        assertEquals(3_000, decision.rebufferMs());
        assertFalse(decision.loweringEligible());
        assertEquals(ExoPlaybackThresholdPolicy.Reason.BASELINE, decision.reason());
    }

    @Test
    public void localResourceKeepsDedicatedLocalThresholds() {
        ExoPlaybackThresholdPolicy.Decision decision = resolve(inputs(
                resource(
                        PlaybackAutoContext.Protocol.LOCAL,
                        PlaybackAutoContext.StreamKind.VOD,
                        PlaybackAutoContext.TransferUnit.CONTINUOUS,
                        null),
                path(PlaybackRoute.Location.LOCAL),
                ExoThroughputEstimator.Snapshot.empty(),
                ForwardBufferTrend.Snapshot.unknown(),
                8_000,
                15_000,
                0,
                PlaybackAutoContext.Confidence.UNKNOWN,
                0,
                0,
                false,
                -1,
                0,
                -1));

        assertEquals(500, decision.startBufferMs());
        assertEquals(1_000, decision.rebufferMs());
        assertEquals(
                ExoPlaybackThresholdPolicy.Reason.LOCAL_RESOURCE,
                decision.reason());
    }

    @Test
    public void hlsVodThresholdsAlignToCompleteSegment() {
        PlaybackAutoContext.ManifestFacts manifest = manifest(
                6_000L, null, null, false);
        ExoPlaybackThresholdPolicy.Decision decision = resolve(inputs(
                resource(
                        PlaybackAutoContext.Protocol.HLS,
                        PlaybackAutoContext.StreamKind.VOD,
                        PlaybackAutoContext.TransferUnit.SEGMENT,
                        manifest),
                path(PlaybackRoute.Location.REMOTE),
                ExoThroughputEstimator.Snapshot.empty(),
                ForwardBufferTrend.Snapshot.unknown(),
                1_500,
                3_000,
                0,
                PlaybackAutoContext.Confidence.UNKNOWN,
                0,
                0,
                false,
                -1,
                0,
                -1));

        assertEquals(6_000, decision.boundaryMs());
        assertEquals(6_000, decision.startBufferMs());
        assertEquals(6_000, decision.rebufferMs());
        assertTrue(decision.segmented());
    }

    @Test
    public void missingManifestBoundaryKeepsExistingThresholds() {
        ExoPlaybackThresholdPolicy.Decision decision = resolve(inputs(
                resource(
                        PlaybackAutoContext.Protocol.DASH,
                        PlaybackAutoContext.StreamKind.VOD,
                        PlaybackAutoContext.TransferUnit.SEGMENT,
                        null),
                path(PlaybackRoute.Location.REMOTE),
                ExoThroughputEstimator.Snapshot.empty(),
                ForwardBufferTrend.Snapshot.unknown(),
                1_500,
                3_000,
                0,
                PlaybackAutoContext.Confidence.UNKNOWN,
                0,
                0,
                false,
                -1,
                0,
                -1));

        assertEquals(0, decision.boundaryMs());
        assertEquals(1_500, decision.startBufferMs());
        assertEquals(3_000, decision.rebufferMs());
    }

    @Test
    public void lowLatencyHlsUsesPartAndHoldBackBoundaries() {
        PlaybackAutoContext.ManifestFacts manifest = manifest(
                6_000L, 500L, 1_500L, true);
        ExoPlaybackThresholdPolicy.Decision decision = resolve(inputs(
                resource(
                        PlaybackAutoContext.Protocol.HLS,
                        PlaybackAutoContext.StreamKind.LOW_LATENCY_LIVE,
                        PlaybackAutoContext.TransferUnit.PART,
                        manifest),
                path(PlaybackRoute.Location.REMOTE),
                stableThroughput(4_000_000, 10_000_000),
                positiveTrend(90_000, 12_000),
                1_500,
                3_000,
                4_000_000,
                PlaybackAutoContext.Confidence.MEDIUM,
                0,
                0,
                false,
                -1,
                2_000,
                -1));

        assertEquals(500, decision.boundaryMs());
        assertEquals(1_000, decision.startBufferMs());
        assertEquals(1_500, decision.rebufferMs());
        assertTrue(decision.loweringEligible());
    }

    @Test
    public void lowLatencyMissingPartDoesNotWaitPastHoldBack() {
        PlaybackAutoContext.ManifestFacts manifest = manifest(
                6_000L, null, 1_500L, true);
        ExoPlaybackThresholdPolicy.Decision decision = resolve(inputs(
                resource(
                        PlaybackAutoContext.Protocol.HLS,
                        PlaybackAutoContext.StreamKind.LOW_LATENCY_LIVE,
                        PlaybackAutoContext.TransferUnit.PART,
                        manifest),
                path(PlaybackRoute.Location.REMOTE),
                ExoThroughputEstimator.Snapshot.empty(),
                ForwardBufferTrend.Snapshot.unknown(),
                1_500,
                3_000,
                0,
                PlaybackAutoContext.Confidence.UNKNOWN,
                0,
                0,
                false,
                -1,
                0,
                -1));

        assertEquals(6_000, decision.boundaryMs());
        assertEquals(1_500, decision.startBufferMs());
        assertEquals(1_500, decision.rebufferMs());
    }

    @Test
    public void externalLoopbackUsesConservativeFloorAndCannotLower() {
        ExoPlaybackThresholdPolicy.Decision decision = resolve(inputs(
                resource(
                        PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                        PlaybackAutoContext.StreamKind.VOD,
                        PlaybackAutoContext.TransferUnit.CONTINUOUS,
                        null),
                externalPath(),
                stableThroughput(10_000_000, 30_000_000),
                positiveTrend(90_000, 30_000),
                1_500,
                3_000,
                10_000_000,
                PlaybackAutoContext.Confidence.HIGH,
                0,
                0,
                false,
                -1,
                30_000,
                -1));

        assertEquals(3_000, decision.startBufferMs());
        assertEquals(5_000, decision.rebufferMs());
        assertTrue(decision.conservativePath());
        assertFalse(decision.loweringEligible());
    }

    @Test
    public void throughputBelowMediaDemandRaisesImmediately() {
        ExoPlaybackThresholdPolicy.Decision decision = resolve(inputs(
                progressiveRemote(),
                path(PlaybackRoute.Location.REMOTE),
                throughput(8_000_000, 8_000_000, 10_000_000, 200,
                        PlaybackAutoContext.Confidence.MEDIUM,
                        ExoThroughputEstimator.Action.DECREASE,
                        ExoThroughputEstimator.Reason.FAST_DECREASE),
                ForwardBufferTrend.Snapshot.unknown(),
                1_500,
                3_000,
                10_000_000,
                PlaybackAutoContext.Confidence.MEDIUM,
                0,
                0,
                false,
                -1,
                0,
                -1));

        assertEquals(8_000, decision.startBufferMs());
        assertEquals(15_000, decision.rebufferMs());
        assertEquals(
                ExoPlaybackThresholdPolicy.RiskLevel.CRITICAL,
                decision.riskLevel());
        assertEquals(800, decision.throughputRatioPermille());
    }

    @Test
    public void highPredictionErrorRaisesWithoutTrustingFastEstimate() {
        ExoPlaybackThresholdPolicy.Decision decision = resolve(inputs(
                progressiveRemote(),
                path(PlaybackRoute.Location.REMOTE),
                throughput(30_000_000, 30_000_000, 30_000_000, 1_200,
                        PlaybackAutoContext.Confidence.MEDIUM,
                        ExoThroughputEstimator.Action.HOLD,
                        ExoThroughputEstimator.Reason.HIGH_PREDICTION_ERROR),
                ForwardBufferTrend.Snapshot.unknown(),
                1_500,
                3_000,
                10_000_000,
                PlaybackAutoContext.Confidence.MEDIUM,
                0,
                0,
                false,
                -1,
                0,
                -1));

        assertEquals(5_000, decision.startBufferMs());
        assertEquals(8_000, decision.rebufferMs());
        assertEquals(
                ExoPlaybackThresholdPolicy.Reason.PREDICTION_ERROR,
                decision.reason());
    }

    @Test
    public void shortWindowDropRaisesBeforeLongWindowCatchesUp() {
        ExoPlaybackThresholdPolicy.Decision decision = resolve(inputs(
                progressiveRemote(),
                path(PlaybackRoute.Location.REMOTE),
                throughput(14_000_000, 12_000_000, 20_000_000, 200,
                        PlaybackAutoContext.Confidence.MEDIUM,
                        ExoThroughputEstimator.Action.HOLD,
                        ExoThroughputEstimator.Reason.STABLE_HOLD),
                ForwardBufferTrend.Snapshot.unknown(),
                1_500,
                3_000,
                10_000_000,
                PlaybackAutoContext.Confidence.MEDIUM,
                0,
                0,
                false,
                -1,
                0,
                -1));

        assertEquals(5_000, decision.startBufferMs());
        assertEquals(8_000, decision.rebufferMs());
        assertEquals(
                ExoPlaybackThresholdPolicy.Reason.NETWORK_VOLATILITY,
                decision.reason());
    }

    @Test
    public void recentTimeToEmptyCanOpenCriticalSafetyGate() {
        ForwardBufferTrend.Snapshot draining = new ForwardBufferTrend.Snapshot(
                -1_000,
                -1_000,
                -700,
                20_000,
                6,
                ForwardBufferTrend.Confidence.MEDIUM,
                5_000,
                NOW - 1_000);
        ExoPlaybackThresholdPolicy.Decision decision = resolve(inputs(
                progressiveRemote(),
                path(PlaybackRoute.Location.REMOTE),
                ExoThroughputEstimator.Snapshot.empty(),
                draining,
                1_500,
                3_000,
                0,
                PlaybackAutoContext.Confidence.UNKNOWN,
                0,
                0,
                false,
                -1,
                5_000,
                -1));

        assertEquals(5_000, decision.timeToEmptyMs());
        assertEquals(8_000, decision.startBufferMs());
        assertEquals(15_000, decision.rebufferMs());
        assertEquals(
                ExoPlaybackThresholdPolicy.Reason.TIME_TO_EMPTY,
                decision.reason());
    }

    @Test
    public void staleTrendCannotDriveAThresholdChange() {
        ForwardBufferTrend.Snapshot stale = new ForwardBufferTrend.Snapshot(
                -1_000,
                -1_000,
                -1_000,
                20_000,
                6,
                ForwardBufferTrend.Confidence.MEDIUM,
                2_000,
                NOW - ExoPlaybackThresholdPolicy.TREND_MAX_AGE_MS - 1);
        ExoPlaybackThresholdPolicy.Decision decision = resolve(inputs(
                progressiveRemote(),
                path(PlaybackRoute.Location.REMOTE),
                ExoThroughputEstimator.Snapshot.empty(),
                stale,
                1_500,
                3_000,
                0,
                PlaybackAutoContext.Confidence.UNKNOWN,
                0,
                0,
                false,
                -1,
                2_000,
                -1));

        assertEquals(1_500, decision.startBufferMs());
        assertEquals(3_000, decision.rebufferMs());
        assertFalse(decision.trendUsable());
    }

    @Test
    public void segmentDurationAlsoDefinesTrendEvidenceWindow() {
        ExoPlaybackThresholdPolicy.Decision decision = resolve(inputs(
                resource(
                        PlaybackAutoContext.Protocol.HLS,
                        PlaybackAutoContext.StreamKind.VOD,
                        PlaybackAutoContext.TransferUnit.SEGMENT,
                        manifest(6_000L, null, null, false)),
                path(PlaybackRoute.Location.REMOTE),
                ExoThroughputEstimator.Snapshot.empty(),
                ForwardBufferTrend.Snapshot.unknown(),
                1_500,
                3_000,
                0,
                PlaybackAutoContext.Confidence.UNKNOWN,
                0,
                0,
                false,
                -1,
                0,
                -1));

        assertEquals(18_000, decision.trendThresholds().minimumWindowMs());
        assertEquals(18_000, decision.trendThresholds().warningTimeToEmptyMs());
        assertEquals(12_000, decision.trendThresholds().criticalTimeToEmptyMs());
        assertEquals(-333, decision.trendThresholds().drainSlopeMsPerSecond());
    }

    @Test
    public void stableLanCanSelectFastThresholdsAfterStrongEvidence() {
        ExoPlaybackThresholdPolicy.Decision decision = resolve(inputs(
                progressiveRemote(),
                path(PlaybackRoute.Location.LAN_PRIVATE),
                stableThroughput(10_000_000, 30_000_000),
                positiveTrend(90_000, 30_000),
                5_000,
                8_000,
                10_000_000,
                PlaybackAutoContext.Confidence.MEDIUM,
                0,
                0,
                false,
                -1,
                30_000,
                -1));

        assertEquals(1_000, decision.startBufferMs());
        assertEquals(1_500, decision.rebufferMs());
        assertTrue(decision.loweringEligible());
        assertEquals(
                ExoPlaybackThresholdPolicy.Reason.STABLE_RECOVERY,
                decision.reason());
    }

    @Test
    public void recentRepeatedRebufferRaisesRecoveryFloor() {
        ExoPlaybackThresholdPolicy.Decision decision = resolve(inputs(
                progressiveRemote(),
                path(PlaybackRoute.Location.REMOTE),
                ExoThroughputEstimator.Snapshot.empty(),
                ForwardBufferTrend.Snapshot.unknown(),
                1_500,
                3_000,
                0,
                PlaybackAutoContext.Confidence.UNKNOWN,
                3,
                15_000,
                true,
                0,
                0,
                -1));

        assertEquals(5_000, decision.startBufferMs());
        assertEquals(8_000, decision.rebufferMs());
        assertEquals(
                ExoPlaybackThresholdPolicy.Reason.REBUFFER_HISTORY,
                decision.reason());
    }

    @Test
    public void liveOffsetCapsRiskThresholdToAvoidUnboundedLag() {
        ExoPlaybackThresholdPolicy.Decision decision = resolve(inputs(
                resource(
                        PlaybackAutoContext.Protocol.HLS,
                        PlaybackAutoContext.StreamKind.LIVE,
                        PlaybackAutoContext.TransferUnit.SEGMENT,
                        manifest(2_000L, null, null, false)),
                path(PlaybackRoute.Location.REMOTE),
                ExoThroughputEstimator.Snapshot.empty(),
                ForwardBufferTrend.Snapshot.unknown(),
                8_000,
                15_000,
                0,
                PlaybackAutoContext.Confidence.UNKNOWN,
                0,
                0,
                false,
                -1,
                0,
                4_000));

        assertEquals(2_000, decision.startBufferMs());
        assertEquals(2_000, decision.rebufferMs());
    }

    @Test
    public void throughputWithoutReliableMediaDemandCannotLower() {
        ExoPlaybackThresholdPolicy.Decision decision = resolve(inputs(
                progressiveRemote(),
                path(PlaybackRoute.Location.REMOTE),
                stableThroughput(0, 30_000_000),
                positiveTrend(90_000, 30_000),
                5_000,
                8_000,
                0,
                PlaybackAutoContext.Confidence.UNKNOWN,
                0,
                0,
                false,
                -1,
                30_000,
                -1));

        assertEquals(5_000, decision.startBufferMs());
        assertEquals(8_000, decision.rebufferMs());
        assertFalse(decision.loweringEligible());
    }

    private static ExoPlaybackThresholdPolicy.Decision resolve(
            ExoPlaybackThresholdPolicy.Inputs inputs) {
        return ExoPlaybackThresholdPolicy.resolve(inputs);
    }

    private static ExoPlaybackThresholdPolicy.Inputs inputs(
            PlaybackAutoContext.ResourceFacts resource,
            PlaybackAutoContext.PathFacts path,
            ExoThroughputEstimator.Snapshot throughput,
            ForwardBufferTrend.Snapshot trend,
            int configuredStartMs,
            int configuredRebufferMs,
            long mediaBitrate,
            PlaybackAutoContext.Confidence mediaConfidence,
            int rebufferCount,
            long rebufferTotalMs,
            boolean currentlyRebuffering,
            long lastRebufferAgeMs,
            long bufferedMs,
            long targetLiveOffsetMs) {
        return new ExoPlaybackThresholdPolicy.Inputs(
                SESSION,
                configuredStartMs,
                configuredRebufferMs,
                resource,
                path,
                throughput,
                trend,
                bufferedMs,
                mediaBitrate,
                mediaConfidence,
                rebufferCount,
                rebufferTotalMs,
                currentlyRebuffering,
                lastRebufferAgeMs,
                targetLiveOffsetMs,
                NOW);
    }

    private static PlaybackAutoContext.ResourceFacts progressiveRemote() {
        return resource(
                PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                PlaybackAutoContext.StreamKind.VOD,
                PlaybackAutoContext.TransferUnit.CONTINUOUS,
                null);
    }

    private static PlaybackAutoContext.ResourceFacts resource(
            PlaybackAutoContext.Protocol protocol,
            PlaybackAutoContext.StreamKind streamKind,
            PlaybackAutoContext.TransferUnit transferUnit,
            PlaybackAutoContext.ManifestFacts manifest) {
        PlaybackAutoContext.Fact<PlaybackAutoContext.ManifestFacts> manifestFact =
                manifest == null
                        ? PlaybackAutoContext.Fact.unknown(
                        PlaybackAutoContext.ManifestFacts.unknown())
                        : PlaybackAutoContext.Fact.withTtl(
                        manifest,
                        PlaybackAutoContext.ValueSource.MANIFEST,
                        PlaybackAutoContext.Confidence.HIGH,
                        NOW,
                        60_000);
        return new PlaybackAutoContext.ResourceFacts(
                PlaybackAutoContext.Fact.forSession(
                        protocol,
                        PlaybackAutoContext.ValueSource.PLAYBACK_REQUEST,
                        PlaybackAutoContext.Confidence.HIGH,
                        NOW),
                PlaybackAutoContext.Fact.forSession(
                        streamKind,
                        PlaybackAutoContext.ValueSource.MANIFEST,
                        PlaybackAutoContext.Confidence.HIGH,
                        NOW),
                PlaybackAutoContext.Fact.unknown(
                        PlaybackAutoContext.RangeSupport.UNKNOWN),
                PlaybackAutoContext.Fact.forSession(
                        transferUnit,
                        PlaybackAutoContext.ValueSource.MANIFEST,
                        PlaybackAutoContext.Confidence.HIGH,
                        NOW),
                manifestFact);
    }

    private static PlaybackAutoContext.ManifestFacts manifest(
            Long targetDurationMs,
            Long partDurationMs,
            Long holdBackMs,
            boolean lowLatency) {
        return new PlaybackAutoContext.ManifestFacts(
                PlaybackAutoContext.ManifestKind.HLS_MEDIA,
                true,
                targetDurationMs,
                partDurationMs,
                holdBackMs,
                1,
                false,
                lowLatency);
    }

    private static PlaybackAutoContext.PathFacts path(
            PlaybackRoute.Location location) {
        PlaybackRoute route = switch (location) {
            case LOCAL -> PlaybackRoute.OTHER;
            case LAN_PRIVATE -> PlaybackRoute.DIRECT_REMOTE_HTTP;
            default -> PlaybackRoute.DIRECT_REMOTE_HTTP;
        };
        PlaybackRoute.Owner owner = location == PlaybackRoute.Location.LOCAL
                ? PlaybackRoute.Owner.UNKNOWN : PlaybackRoute.Owner.REMOTE_ORIGIN;
        PlaybackRoute.Evidence evidence = switch (location) {
            case LOCAL -> PlaybackRoute.Evidence.LOCAL_SCHEME;
            case LAN_PRIVATE -> PlaybackRoute.Evidence.PRIVATE_HOST;
            default -> PlaybackRoute.Evidence.REMOTE_HOST;
        };
        return PlaybackAutoContext.PathFacts.fromResolution(
                new PlaybackRoute.Resolution(
                        route,
                        owner,
                        evidence,
                        PlaybackRoute.Confidence.CONFIRMED,
                        location == PlaybackRoute.Location.LOCAL ? "file" : "https",
                        false,
                        location),
                NOW);
    }

    private static PlaybackAutoContext.PathFacts externalPath() {
        return PlaybackAutoContext.PathFacts.fromResolution(
                new PlaybackRoute.Resolution(
                        PlaybackRoute.EXTERNAL_LOOPBACK_PROXY,
                        PlaybackRoute.Owner.EXTERNAL_OR_UNKNOWN_LOOPBACK,
                        PlaybackRoute.Evidence.UNREGISTERED_LOOPBACK_PORT,
                        PlaybackRoute.Confidence.CONFIRMED,
                        "http",
                        true,
                        PlaybackRoute.Location.EXTERNAL_LOOPBACK),
                NOW);
    }

    private static ExoThroughputEstimator.Snapshot stableThroughput(
            long mediaBitrate,
            long effectiveBitrate) {
        return throughput(
                effectiveBitrate,
                effectiveBitrate,
                effectiveBitrate,
                150,
                PlaybackAutoContext.Confidence.HIGH,
                ExoThroughputEstimator.Action.HOLD,
                ExoThroughputEstimator.Reason.STABLE_HOLD);
    }

    private static ExoThroughputEstimator.Snapshot throughput(
            long effective,
            long shortEstimate,
            long longEstimate,
            int errorPermille,
            PlaybackAutoContext.Confidence confidence,
            ExoThroughputEstimator.Action action,
            ExoThroughputEstimator.Reason reason) {
        return new ExoThroughputEstimator.Snapshot(
                SESSION,
                effective,
                shortEstimate,
                longEstimate,
                effective,
                6,
                4,
                5,
                12_000,
                30_000,
                errorPermille,
                confidence,
                ExoThroughputPathPolicy.Trust.TRUSTED,
                PlaybackAutoContext.Confidence.HIGH,
                false,
                effective,
                action,
                reason,
                NOW - 1_000);
    }

    private static ForwardBufferTrend.Snapshot positiveTrend(
            long sampledAtMs,
            long bufferedMs) {
        return new ForwardBufferTrend.Snapshot(
                500,
                500,
                300,
                30_000,
                8,
                ForwardBufferTrend.Confidence.HIGH,
                bufferedMs,
                sampledAtMs);
    }
}
