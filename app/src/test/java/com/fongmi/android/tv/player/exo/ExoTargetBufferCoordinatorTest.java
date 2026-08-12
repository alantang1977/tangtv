package com.fongmi.android.tv.player.exo;

import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.PlaybackAutoContextStore;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ExoTargetBufferCoordinatorTest {

    @Test
    public void publishedTargetIsSharedForCurrentSession() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        ExoTargetBufferCoordinator coordinator = new ExoTargetBufferCoordinator(store);
        PlaybackAutoContext.SessionToken session = store.beginSession("p-target-1", 10);
        ExoTargetBufferPolicy.Decision decision = decision(20_000_000L);

        assertTrue(coordinator.publish(session, decision, 20));
        assertEquals(decision, coordinator.currentDecision());
        assertEquals(decision, coordinator.currentDecision(session));
        assertEquals(decision.targetBytes(), coordinator.currentTargetBytesOr(mib(16)));
    }

    @Test
    public void replacementSessionCannotReadOrPublishOldTarget() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        ExoTargetBufferCoordinator coordinator = new ExoTargetBufferCoordinator(store);
        PlaybackAutoContext.SessionToken first = store.beginSession("p-target-2a", 10);
        assertTrue(coordinator.publish(first, decision(8_000_000L), 20));

        PlaybackAutoContext.SessionToken second = store.beginSession("p-target-2b", 30);

        assertNull(coordinator.currentDecision());
        assertNull(coordinator.currentDecision(first));
        assertFalse(coordinator.publish(first, decision(20_000_000L), 40));
        assertEquals(mib(16), coordinator.currentTargetBytesOr(mib(16)));
        assertTrue(coordinator.publish(second, decision(20_000_000L), 50));
        assertEquals(mib(96), coordinator.currentTargetBytesOr(mib(16)));
    }

    @Test
    public void inactiveSessionAndNullDecisionAreRejected() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        ExoTargetBufferCoordinator coordinator = new ExoTargetBufferCoordinator(store);

        assertFalse(coordinator.publish(PlaybackAutoContext.SessionToken.none(), decision(8_000_000L), 10));
        PlaybackAutoContext.SessionToken session = store.beginSession("p-target-3", 20);
        assertFalse(coordinator.publish(session, null, 30));
        assertEquals(mib(24), coordinator.currentTargetBytesOr(mib(24)));
    }

    @Test
    public void effectiveRuntimeTargetCanShrinkWithoutReplacingBaselineDecision() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        ExoTargetBufferCoordinator coordinator = new ExoTargetBufferCoordinator(store);
        PlaybackAutoContext.SessionToken session = store.beginSession("p-target-4", 10);
        ExoTargetBufferPolicy.Decision baseline = decision(20_000_000L);
        assertTrue(coordinator.publish(session, baseline, 20));

        assertTrue(coordinator.publishEffectiveTarget(session, mib(24), true, 30));

        assertEquals(baseline, coordinator.currentDecision(session));
        assertEquals(mib(24), coordinator.currentTargetBytesOr(mib(16)));
        assertFalse(coordinator.publishEffectiveTarget(
                PlaybackAutoContext.SessionToken.none(), mib(16), true, 40));
    }

    @Test
    public void baselineRefreshCannotBrieflyUndoRuntimePressureLimit() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        ExoTargetBufferCoordinator coordinator = new ExoTargetBufferCoordinator(store);
        PlaybackAutoContext.SessionToken session = store.beginSession("p-target-5", 10);
        assertTrue(coordinator.publish(session, decision(20_000_000L), 20));
        assertTrue(coordinator.publishEffectiveTarget(session, mib(24), true, 30));

        ExoTargetBufferPolicy.Decision larger = decision(30_000_000L);
        assertTrue(coordinator.publish(session, larger, 40));

        assertEquals(larger, coordinator.currentDecision(session));
        assertEquals(mib(24), coordinator.currentTargetBytesOr(mib(16)));
    }

    @Test
    public void runtimePressureFlagIsPreservedEvenAtTheCurrentBaselineFloor() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        ExoTargetBufferCoordinator coordinator = new ExoTargetBufferCoordinator(store);
        PlaybackAutoContext.SessionToken session = store.beginSession("p-target-6", 10);
        ExoTargetBufferPolicy.Decision floor = decision(1_000_000L);
        assertEquals(mib(16), floor.targetBytes());
        assertTrue(coordinator.publish(session, floor, 20));
        assertTrue(coordinator.publishEffectiveTarget(session, mib(16), true, 30));

        assertTrue(coordinator.publish(session, decision(20_000_000L), 40));

        assertEquals(mib(16), coordinator.currentTargetBytesOr(mib(24)));
    }

    private static ExoTargetBufferPolicy.Decision decision(long averageBitrate) {
        ExoTargetBufferPolicy.MediaDemand demand = new ExoTargetBufferPolicy.MediaDemand(
                averageBitrate,
                ExoTargetBufferPolicy.DemandSource.SELECTED_TRACK,
                PlaybackAutoContext.Confidence.MEDIUM,
                averageBitrate,
                ExoTargetBufferPolicy.DemandSource.SELECTED_TRACK,
                PlaybackAutoContext.Confidence.MEDIUM);
        return ExoTargetBufferPolicy.resolve(
                demand,
                0,
                ExoBufferBudget.calculate(
                        ExoBufferBudget.MAX_TARGET_BYTES,
                        1024L * 1024L * 1024L,
                        false),
                PlaybackAutoContext.DeviceFacts.unknown(),
                0);
    }

    private static int mib(int value) {
        return value * 1024 * 1024;
    }
}
