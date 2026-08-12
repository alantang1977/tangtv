package com.fongmi.android.tv.player.exo;

import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.PlaybackRoute;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ExoPlaybackThresholdCoordinatorTest {

    @Test
    public void riskRaisesBothThresholdsImmediately() {
        ExoPlaybackThresholdCoordinator coordinator =
                new ExoPlaybackThresholdCoordinator();
        PlaybackAutoContext.SessionToken session = session("raise", 1);
        coordinator.observe(inputs(
                session, 0, 1_500, 3_000, null, 0, 0, false));

        ExoPlaybackThresholdCoordinator.Update update = coordinator.observe(inputs(
                session,
                1_000,
                1_500,
                3_000,
                throughput(session, 1_000, 8_000_000),
                10_000_000,
                0,
                false));

        assertTrue(update.changed());
        assertEquals(ExoPlaybackThresholdCoordinator.Action.RAISE, update.action());
        assertEquals(8_000, update.startBufferMs());
        assertEquals(15_000, update.rebufferMs());
    }

    @Test
    public void stableEvidenceLowersOnlyAfterHysteresisAndOneTierPerStep() {
        ExoPlaybackThresholdCoordinator coordinator =
                new ExoPlaybackThresholdCoordinator();
        PlaybackAutoContext.SessionToken session = session("lower", 1);
        coordinator.observe(inputs(
                session, 0, 5_000, 8_000, null, 0, 0, false));

        ExoPlaybackThresholdCoordinator.Update firstStable = coordinator.observe(
                stableInputs(session, 10_000, 5_000, 8_000));
        ExoPlaybackThresholdCoordinator.Update beforeWindow = coordinator.observe(
                stableInputs(session, 39_999, 5_000, 8_000));
        ExoPlaybackThresholdCoordinator.Update firstStep = coordinator.observe(
                stableInputs(session, 40_000, 5_000, 8_000));
        ExoPlaybackThresholdCoordinator.Update beforeNextStep = coordinator.observe(
                stableInputs(session, 54_999, 5_000, 8_000));
        ExoPlaybackThresholdCoordinator.Update secondStep = coordinator.observe(
                stableInputs(session, 55_000, 5_000, 8_000));

        assertFalse(firstStable.changed());
        assertFalse(beforeWindow.changed());
        assertEquals(3_000, firstStep.startBufferMs());
        assertEquals(5_000, firstStep.rebufferMs());
        assertEquals(ExoPlaybackThresholdCoordinator.Action.LOWER, firstStep.action());
        assertFalse(beforeNextStep.changed());
        assertEquals(2_000, secondStep.startBufferMs());
        assertEquals(3_000, secondStep.rebufferMs());
    }

    @Test
    public void activeEpisodeKeepsItsLockedThresholdWhileNextEpisodeTightens() {
        ExoPlaybackThresholdCoordinator coordinator =
                new ExoPlaybackThresholdCoordinator();
        PlaybackAutoContext.SessionToken session = session("lock", 1);
        ExoPlaybackThresholdCoordinator.Selection initial = coordinator.lockEpisode(
                ExoPlaybackThresholdCoordinator.Episode.STARTUP,
                inputs(session, 0, 1_500, 3_000, null, 0, 0, false));
        coordinator.observe(inputs(
                session,
                1_000,
                1_500,
                3_000,
                throughput(session, 1_000, 8_000_000),
                10_000_000,
                0,
                false));

        ExoPlaybackThresholdCoordinator.Selection stillLocked =
                coordinator.lockEpisode(
                        ExoPlaybackThresholdCoordinator.Episode.STARTUP,
                        inputs(session, 2_000, 1_500, 3_000,
                                throughput(session, 2_000, 8_000_000),
                                10_000_000, 0, false));
        coordinator.endEpisode(session);
        ExoPlaybackThresholdCoordinator.Selection next = coordinator.lockEpisode(
                ExoPlaybackThresholdCoordinator.Episode.REBUFFER,
                inputs(session, 3_000, 1_500, 3_000,
                        throughput(session, 3_000, 8_000_000),
                        10_000_000, 1, true));

        assertTrue(initial.newlyLocked());
        assertEquals(1_500, initial.thresholdMs());
        assertFalse(stillLocked.newlyLocked());
        assertEquals(1_500, stillLocked.thresholdMs());
        assertTrue(next.newlyLocked());
        assertEquals(15_000, next.thresholdMs());
    }

    @Test
    public void newSessionCannotReuseOldThresholdOrEpisodeLock() {
        ExoPlaybackThresholdCoordinator coordinator =
                new ExoPlaybackThresholdCoordinator();
        PlaybackAutoContext.SessionToken first = session("replace-a", 1);
        PlaybackAutoContext.SessionToken second = session("replace-b", 2);
        coordinator.lockEpisode(
                ExoPlaybackThresholdCoordinator.Episode.REBUFFER,
                inputs(first, 0, 1_500, 3_000,
                        throughput(first, 0, 8_000_000),
                        10_000_000, 1, true));

        ExoPlaybackThresholdCoordinator.Update replacement = coordinator.observe(
                inputs(second, 1_000, 1_500, 3_000, null, 0, 0, false));
        coordinator.endEpisode(first);

        assertEquals(second, replacement.session());
        assertEquals(1_500, replacement.startBufferMs());
        assertEquals(3_000, replacement.rebufferMs());
        assertNull(coordinator.snapshot().lockedEpisode());
    }

    @Test
    public void discontinuityClearsEpisodeButKeepsConservativeState() {
        ExoPlaybackThresholdCoordinator coordinator =
                new ExoPlaybackThresholdCoordinator();
        PlaybackAutoContext.SessionToken session = session("seek", 1);
        coordinator.lockEpisode(
                ExoPlaybackThresholdCoordinator.Episode.REBUFFER,
                inputs(session, 0, 1_500, 3_000,
                        throughput(session, 0, 8_000_000),
                        10_000_000, 1, true));

        coordinator.disrupt(session);
        ExoPlaybackThresholdCoordinator.Snapshot snapshot = coordinator.snapshot();

        assertNull(snapshot.lockedEpisode());
        assertEquals(8_000, snapshot.startBufferMs());
        assertEquals(15_000, snapshot.rebufferMs());
    }

    @Test
    public void networkResetImmediatelyRestoresConfiguredSafetyFloor() {
        ExoPlaybackThresholdCoordinator coordinator =
                new ExoPlaybackThresholdCoordinator();
        PlaybackAutoContext.SessionToken session = session("network", 1);
        coordinator.observe(stableInputs(session, 0, 5_000, 8_000));

        ExoPlaybackThresholdCoordinator.Update update = coordinator.observe(
                networkResetInputs(session, 1_000, 5_000, 8_000));

        assertTrue(update.changed());
        assertEquals(ExoPlaybackThresholdCoordinator.Action.RAISE, update.action());
        assertEquals(5_000, update.startBufferMs());
        assertEquals(8_000, update.rebufferMs());
    }

    @Test
    public void lowLatencyHoldBackCapsExistingVodThresholdImmediately() {
        ExoPlaybackThresholdCoordinator coordinator =
                new ExoPlaybackThresholdCoordinator();
        PlaybackAutoContext.SessionToken session = session("livecap", 1);
        coordinator.lockEpisode(
                ExoPlaybackThresholdCoordinator.Episode.STARTUP,
                inputs(session, 0, 8_000, 15_000, null, 0, 0, false));

        ExoPlaybackThresholdCoordinator.Selection selection =
                coordinator.lockEpisode(
                        ExoPlaybackThresholdCoordinator.Episode.STARTUP,
                        lowLatencyInputs(session, 1_000, 8_000, 15_000));

        assertTrue(selection.newlyLocked());
        assertEquals(ExoPlaybackThresholdCoordinator.Action.CAP, selection.action());
        assertEquals(1_500, selection.startBufferMs());
        assertEquals(1_500, selection.rebufferMs());
    }

    private static ExoPlaybackThresholdPolicy.Inputs stableInputs(
            PlaybackAutoContext.SessionToken session,
            long now,
            int startMs,
            int rebufferMs) {
        return inputs(
                session,
                now,
                startMs,
                rebufferMs,
                throughput(session, now, 30_000_000),
                10_000_000,
                0,
                false);
    }

    private static ExoPlaybackThresholdPolicy.Inputs networkResetInputs(
            PlaybackAutoContext.SessionToken session,
            long now,
            int startMs,
            int rebufferMs) {
        ExoThroughputEstimator.Snapshot reset = new ExoThroughputEstimator.Snapshot(
                session,
                30_000_000,
                0,
                0,
                30_000_000,
                0,
                0,
                0,
                0,
                0,
                -1,
                PlaybackAutoContext.Confidence.LOW,
                ExoThroughputPathPolicy.Trust.LIMITED,
                PlaybackAutoContext.Confidence.LOW,
                false,
                0,
                ExoThroughputEstimator.Action.RESET,
                ExoThroughputEstimator.Reason.NETWORK_RESET,
                now);
        return inputs(
                session,
                now,
                startMs,
                rebufferMs,
                reset,
                10_000_000,
                0,
                false);
    }

    private static ExoPlaybackThresholdPolicy.Inputs lowLatencyInputs(
            PlaybackAutoContext.SessionToken session,
            long now,
            int startMs,
            int rebufferMs) {
        PlaybackAutoContext.ManifestFacts manifest =
                new PlaybackAutoContext.ManifestFacts(
                        PlaybackAutoContext.ManifestKind.HLS_MEDIA,
                        false,
                        6_000L,
                        500L,
                        1_500L,
                        1,
                        false,
                        true);
        PlaybackAutoContext.ResourceFacts resource =
                new PlaybackAutoContext.ResourceFacts(
                        PlaybackAutoContext.Fact.forSession(
                                PlaybackAutoContext.Protocol.HLS,
                                PlaybackAutoContext.ValueSource.MANIFEST,
                                PlaybackAutoContext.Confidence.HIGH,
                                now),
                        PlaybackAutoContext.Fact.forSession(
                                PlaybackAutoContext.StreamKind.LOW_LATENCY_LIVE,
                                PlaybackAutoContext.ValueSource.MANIFEST,
                                PlaybackAutoContext.Confidence.HIGH,
                                now),
                        PlaybackAutoContext.Fact.unknown(
                                PlaybackAutoContext.RangeSupport.UNKNOWN),
                        PlaybackAutoContext.Fact.forSession(
                                PlaybackAutoContext.TransferUnit.PART,
                                PlaybackAutoContext.ValueSource.MANIFEST,
                                PlaybackAutoContext.Confidence.HIGH,
                                now),
                        PlaybackAutoContext.Fact.withTtl(
                                manifest,
                                PlaybackAutoContext.ValueSource.MANIFEST,
                                PlaybackAutoContext.Confidence.HIGH,
                                now,
                                60_000));
        return new ExoPlaybackThresholdPolicy.Inputs(
                session,
                startMs,
                rebufferMs,
                resource,
                remotePath(now),
                ExoThroughputEstimator.Snapshot.empty(),
                ForwardBufferTrend.Snapshot.unknown(),
                0,
                0,
                PlaybackAutoContext.Confidence.UNKNOWN,
                0,
                0,
                false,
                -1,
                -1,
                now);
    }

    private static ExoPlaybackThresholdPolicy.Inputs inputs(
            PlaybackAutoContext.SessionToken session,
            long now,
            int configuredStartMs,
            int configuredRebufferMs,
            ExoThroughputEstimator.Snapshot throughput,
            long mediaBitrate,
            int rebufferCount,
            boolean rebuffering) {
        return new ExoPlaybackThresholdPolicy.Inputs(
                session,
                configuredStartMs,
                configuredRebufferMs,
                progressive(),
                remotePath(now),
                throughput,
                ForwardBufferTrend.Snapshot.unknown(),
                30_000,
                mediaBitrate,
                mediaBitrate > 0
                        ? PlaybackAutoContext.Confidence.MEDIUM
                        : PlaybackAutoContext.Confidence.UNKNOWN,
                rebufferCount,
                0,
                rebuffering,
                -1,
                -1,
                now);
    }

    private static PlaybackAutoContext.ResourceFacts progressive() {
        return new PlaybackAutoContext.ResourceFacts(
                PlaybackAutoContext.Fact.forSession(
                        PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                        PlaybackAutoContext.ValueSource.PLAYBACK_REQUEST,
                        PlaybackAutoContext.Confidence.HIGH,
                        0),
                PlaybackAutoContext.Fact.forSession(
                        PlaybackAutoContext.StreamKind.VOD,
                        PlaybackAutoContext.ValueSource.PLAYBACK_REQUEST,
                        PlaybackAutoContext.Confidence.MEDIUM,
                        0),
                PlaybackAutoContext.Fact.forSession(
                        PlaybackAutoContext.RangeSupport.SUPPORTED,
                        PlaybackAutoContext.ValueSource.DATA_SOURCE,
                        PlaybackAutoContext.Confidence.HIGH,
                        0),
                PlaybackAutoContext.Fact.forSession(
                        PlaybackAutoContext.TransferUnit.CONTINUOUS,
                        PlaybackAutoContext.ValueSource.DATA_SOURCE,
                        PlaybackAutoContext.Confidence.HIGH,
                        0));
    }

    private static PlaybackAutoContext.PathFacts remotePath(long now) {
        return PlaybackAutoContext.PathFacts.fromResolution(
                new PlaybackRoute.Resolution(
                        PlaybackRoute.DIRECT_REMOTE_HTTP,
                        PlaybackRoute.Owner.REMOTE_ORIGIN,
                        PlaybackRoute.Evidence.REMOTE_HOST,
                        PlaybackRoute.Confidence.CONFIRMED,
                        "https",
                        false,
                        PlaybackRoute.Location.REMOTE),
                now);
    }

    private static ExoThroughputEstimator.Snapshot throughput(
            PlaybackAutoContext.SessionToken session,
            long now,
            long effective) {
        return new ExoThroughputEstimator.Snapshot(
                session,
                effective,
                effective,
                effective,
                effective,
                6,
                4,
                5,
                12_000,
                30_000,
                150,
                PlaybackAutoContext.Confidence.HIGH,
                ExoThroughputPathPolicy.Trust.TRUSTED,
                PlaybackAutoContext.Confidence.HIGH,
                false,
                effective,
                ExoThroughputEstimator.Action.HOLD,
                ExoThroughputEstimator.Reason.STABLE_HOLD,
                now);
    }

    private static PlaybackAutoContext.SessionToken session(
            String suffix,
            long generation) {
        long suffixValue = Integer.toUnsignedLong(suffix.hashCode()) + 1L;
        return new PlaybackAutoContext.SessionToken(
                "p-" + Long.toString(generation + 100L, 36)
                        + "-" + Long.toString(suffixValue, 36),
                generation);
    }
}
