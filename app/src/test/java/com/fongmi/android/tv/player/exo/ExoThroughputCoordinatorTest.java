package com.fongmi.android.tv.player.exo;

import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.PlaybackAutoContextStore;
import com.fongmi.android.tv.player.PlaybackRoute;
import com.fongmi.android.tv.player.PlaybackNetworkIdentityPolicy;
import com.fongmi.android.tv.player.PlaybackSystemConditionCoordinator;
import com.fongmi.android.tv.player.PlaybackThroughputHistory;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExoThroughputCoordinatorTest {

    @Test
    public void sessionReplacementRejectsLateSamplesAndRestoresMedia3Prior() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        ExoThroughputCoordinator coordinator = new ExoThroughputCoordinator(store);
        PlaybackAutoContext.SessionToken first = beginExoSession(store, "p-coord-1", 0);
        coordinator.synchronize(4_000_000L, 0);
        ExoThroughputEstimator.Update accepted = coordinator.observe(
                first, 1_000, 1_000_000, 1_000, 8_000_000L, false, true);

        PlaybackAutoContext.SessionToken second = beginExoSession(store, "p-coord-2", 2_000);
        ExoThroughputEstimator.Snapshot reset = coordinator.synchronize(2_000_000L, 2_000);
        ExoThroughputEstimator.Update late = coordinator.observe(
                first, 3_000, 1_000_000, 1_000, 8_000_000L, false, true);

        assertTrue(accepted.accepted());
        assertEquals(second, reset.session());
        assertEquals(2_000_000L, reset.effectiveEstimateBitsPerSecond());
        assertEquals(0, reset.sampleCount());
        assertFalse(late.accepted());
        assertEquals(ExoThroughputEstimator.Reason.STALE_SESSION, late.reason());
    }

    @Test
    public void networkResetClearsWindowsWithoutInventingZeroEstimate() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        ExoThroughputCoordinator coordinator = new ExoThroughputCoordinator(store);
        PlaybackAutoContext.SessionToken session = beginExoSession(store, "p-coord-3", 0);
        coordinator.synchronize(4_000_000L, 0);
        coordinator.observe(session, 1_000, 1_000_000, 1_000,
                8_000_000L, false, true);

        ExoThroughputEstimator.Snapshot reset =
                coordinator.resetForNetworkChange(1_000_000L, 2_000);

        assertEquals(1_000_000L, reset.effectiveEstimateBitsPerSecond());
        assertEquals(0, reset.sampleCount());
        assertEquals(ExoThroughputEstimator.Reason.NETWORK_RESET, reset.reason());
    }

    @Test
    public void higherNetworkPriorCannotBypassPostResetUpgradeEvidence() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        ExoThroughputCoordinator coordinator = new ExoThroughputCoordinator(store);
        beginExoSession(store, "p-coord3b-1", 0);
        coordinator.synchronize(2_000_000L, 0);

        ExoThroughputEstimator.Snapshot reset =
                coordinator.resetForNetworkChange(8_000_000L, 1_000);

        assertEquals(8_000_000L, reset.rawEstimateBitsPerSecond());
        assertEquals(2_000_000L, reset.effectiveEstimateBitsPerSecond());
        assertEquals(0, reset.sampleCount());
        assertEquals(ExoThroughputEstimator.Reason.NETWORK_RESET, reset.reason());
    }

    @Test
    public void networkCallbackClearsWindowsEvenWhenMedia3PriorIsUnchanged() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        PlaybackSystemConditionCoordinator conditions =
                new PlaybackSystemConditionCoordinator(store);
        ExoThroughputCoordinator coordinator = new ExoThroughputCoordinator(
                store, new ExoThroughputEstimator(), conditions);
        PlaybackAutoContext.SessionToken session = beginExoSession(
                store, "p-coord4-1", 0);
        conditions.beginSession(session);
        coordinator.synchronize(4_000_000L, 0);
        coordinator.observe(session, 1_000, 1_000_000, 1_000,
                8_000_000L, false, true);

        conditions.publish(
                session,
                PlaybackAutoContext.SystemConditionTrigger.NETWORK_CALLBACK,
                new PlaybackAutoContext.NetworkSnapshot(
                        true, true, false, false,
                        PlaybackAutoContext.NetworkTransport.WIFI,
                        PlaybackAutoContext.DataSaverState.DISABLED),
                false,
                null,
                28,
                2_000);

        ExoThroughputEstimator.Snapshot reset = coordinator.snapshot();
        assertEquals(0, reset.sampleCount());
        assertEquals(4_000_000L, reset.effectiveEstimateBitsPerSecond());
        assertEquals(ExoThroughputEstimator.Reason.NETWORK_RESET, reset.reason());
        coordinator.close();
    }

    @Test
    public void sampleCrossingNetworkGenerationIsRejectedBeforeItCanPolluteWindow() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        PlaybackSystemConditionCoordinator conditions =
                new PlaybackSystemConditionCoordinator(store);
        ExoThroughputCoordinator coordinator = new ExoThroughputCoordinator(
                store, new ExoThroughputEstimator(), conditions);
        PlaybackAutoContext.SessionToken session = beginExoSession(
                store, "p-coord5-1", 0);
        conditions.beginSession(session);
        coordinator.synchronize(4_000_000L, 0);
        long sampleGeneration = coordinator.networkGeneration();

        conditions.publish(
                session,
                PlaybackAutoContext.SystemConditionTrigger.NETWORK_CALLBACK,
                new PlaybackAutoContext.NetworkSnapshot(
                        true, true, false, false,
                        PlaybackAutoContext.NetworkTransport.CELLULAR,
                        PlaybackAutoContext.DataSaverState.DISABLED),
                false,
                null,
                28,
                500);
        ExoThroughputEstimator.Update crossed = coordinator.observe(
                session,
                1_000,
                1_000_000,
                1_000,
                8_000_000L,
                false,
                true,
                sampleGeneration);
        ExoThroughputEstimator.Update fresh = coordinator.observe(
                session,
                2_000,
                1_000_000,
                1_000,
                8_000_000L,
                false,
                true,
                coordinator.networkGeneration());

        assertFalse(crossed.accepted());
        assertEquals(
                ExoThroughputEstimator.Reason.NETWORK_CHANGED_DURING_SAMPLE,
                crossed.reason());
        assertEquals(0, crossed.snapshot().sampleCount());
        assertTrue(fresh.accepted());
        assertEquals(1, fresh.snapshot().sampleCount());
        coordinator.close();
    }

    @Test
    public void networkCallbackInvalidatesInFlightSampleBeforeEstimatorInitialization() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        PlaybackSystemConditionCoordinator conditions =
                new PlaybackSystemConditionCoordinator(store);
        ExoThroughputCoordinator coordinator = new ExoThroughputCoordinator(
                store, new ExoThroughputEstimator(), conditions);
        PlaybackAutoContext.SessionToken session = beginExoSession(
                store, "p-coord6-1", 0);
        conditions.beginSession(session);
        long sampleGeneration = coordinator.networkGeneration();

        conditions.publish(
                session,
                PlaybackAutoContext.SystemConditionTrigger.NETWORK_CALLBACK,
                new PlaybackAutoContext.NetworkSnapshot(
                        true, true, false, false,
                        PlaybackAutoContext.NetworkTransport.WIFI,
                        PlaybackAutoContext.DataSaverState.DISABLED),
                false,
                null,
                28,
                500);
        coordinator.synchronize(4_000_000L, 1_000);
        ExoThroughputEstimator.Update crossed = coordinator.observe(
                session,
                1_500,
                1_000_000,
                1_000,
                8_000_000L,
                false,
                true,
                sampleGeneration);

        assertFalse(crossed.accepted());
        assertEquals(
                ExoThroughputEstimator.Reason.NETWORK_CHANGED_DURING_SAMPLE,
                crossed.reason());
        assertEquals(0, coordinator.snapshot().sampleCount());
        coordinator.close();
    }

    @Test
    public void networkCallbackClearsReusableStartupThroughputHistory() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        PlaybackSystemConditionCoordinator conditions =
                new PlaybackSystemConditionCoordinator(store);
        PlaybackThroughputHistory history = new PlaybackThroughputHistory();
        ExoThroughputCoordinator coordinator = new ExoThroughputCoordinator(
                store, new ExoThroughputEstimator(), conditions, history);
        PlaybackAutoContext.SessionToken session = beginExoSession(
                store, "p-history-1", 0);
        assertTrue(conditions.beginSession(session));
        String digest = PlaybackNetworkIdentityPolicy.digest(1001);
        PlaybackAutoContext.NetworkSnapshot network =
                new PlaybackAutoContext.NetworkSnapshot(
                        true, true, false, false,
                        PlaybackAutoContext.NetworkTransport.WIFI,
                        PlaybackAutoContext.DataSaverState.DISABLED,
                        digest);
        assertTrue(conditions.publish(
                session,
                PlaybackAutoContext.SystemConditionTrigger.SESSION_START,
                network,
                false,
                null,
                28,
                1));
        assertTrue(history.record(
                store.snapshot(),
                new PlaybackThroughputHistory.Evidence(
                        20_000_000L,
                        18_000_000L,
                        16_000_000L,
                        4,
                        15_000,
                        200,
                        PlaybackAutoContext.Confidence.HIGH,
                        true,
                        false),
                1).recorded());
        assertTrue(history.lookup(
                store.snapshot(), digest, 50).usable());

        conditions.publish(
                session,
                PlaybackAutoContext.SystemConditionTrigger.NETWORK_CALLBACK,
                network,
                false,
                null,
                28,
                100);

        assertFalse(history.lookup(
                store.snapshot(), digest, 100).usable());
        coordinator.close();
    }

    static PlaybackAutoContext.SessionToken beginExoSession(
            PlaybackAutoContextStore store,
            String traceId,
            long now) {
        PlaybackAutoContext.SessionToken session = store.beginSession(traceId, now);
        store.publishPlaybackFacts(
                session,
                PlaybackAutoContext.Fact.forSession(
                        PlaybackAutoContext.Kernel.EXO,
                        PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                        PlaybackAutoContext.Confidence.HIGH,
                        now),
                PlaybackAutoContext.Fact.forSession(
                        PlaybackAutoContext.DecodeMode.HARDWARE,
                        PlaybackAutoContext.ValueSource.PLAYBACK_REQUEST,
                        PlaybackAutoContext.Confidence.HIGH,
                        now),
                PlaybackAutoContext.PathFacts.fromResolution(
                        new PlaybackRoute.Resolution(
                                PlaybackRoute.DIRECT_REMOTE_HTTP,
                                PlaybackRoute.Owner.REMOTE_ORIGIN,
                                PlaybackRoute.Evidence.REMOTE_HOST,
                                PlaybackRoute.Confidence.CONFIRMED,
                                "https",
                                false,
                                PlaybackRoute.Location.REMOTE),
                        now),
                now);
        return session;
    }
}
