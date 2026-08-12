package com.fongmi.android.tv.player.exo;

import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.PlaybackAutoContextStore;
import com.fongmi.android.tv.player.PlaybackMemoryCoordinator;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ExoMemoryPressureCoordinatorTest {

    @Test
    public void memoryPublicationImmediatelyUpdatesSharedExoDecision() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        PlaybackMemoryCoordinator memory = new PlaybackMemoryCoordinator(store);
        ExoMemoryPressureCoordinator coordinator = new ExoMemoryPressureCoordinator(store, memory);
        PlaybackAutoContext.SessionToken session = store.beginSession("p-em1-1", 0);
        assertTrue(memory.beginSession(session));
        AtomicInteger delivered = new AtomicInteger();
        coordinator.addListener(update -> delivered.incrementAndGet());

        assertTrue(coordinator.publishBaseline(
                session, baseline(), 0, fallback(), PlaybackAutoContext.DeviceFacts.unknown(), 1));
        assertTrue(memory.publish(session, criticalSnapshot(), null, 34, 10));

        ExoMemoryPressurePolicy.Decision decision = coordinator.currentDecision(session);
        assertEquals(mib(16), decision.effectiveTargetBytes());
        assertTrue(decision.preloadPaused());
        assertEquals(decision, coordinator.currentDecision("p-em1-1"));
        assertEquals(2, delivered.get());
        coordinator.close();
    }

    @Test
    public void recoverySamplesFlowThroughCoordinatorOneTierAtATime() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        PlaybackMemoryCoordinator memory = new PlaybackMemoryCoordinator(store);
        ExoMemoryPressureCoordinator coordinator = new ExoMemoryPressureCoordinator(store, memory);
        PlaybackAutoContext.SessionToken session = store.beginSession("p-em2-1", 0);
        assertTrue(memory.beginSession(session));
        assertTrue(coordinator.publishBaseline(
                session, baseline(), 0, fallback(), PlaybackAutoContext.DeviceFacts.unknown(), 1));
        assertTrue(memory.publish(session, criticalSnapshot(), null, 34, 10));
        assertTrue(memory.publish(session, normalSnapshot(), null, 34, 30_010));
        assertEquals(mib(16), coordinator.currentDecision(session).effectiveTargetBytes());

        assertTrue(memory.publish(session, normalSnapshot(), null, 34, 60_010));
        assertEquals(mib(24), coordinator.currentDecision(session).effectiveTargetBytes());
        assertTrue(coordinator.currentDecision(session).preloadPaused());
        coordinator.close();
    }

    @Test
    public void republishingBaselineWithSameMemorySampleDoesNotAdvanceRecovery() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        PlaybackMemoryCoordinator memory = new PlaybackMemoryCoordinator(store);
        ExoMemoryPressureCoordinator coordinator = new ExoMemoryPressureCoordinator(store, memory);
        PlaybackAutoContext.SessionToken session = store.beginSession("p-em3-1", 0);
        assertTrue(memory.beginSession(session));
        assertTrue(coordinator.publishBaseline(
                session, baseline(), 0, fallback(), PlaybackAutoContext.DeviceFacts.unknown(), 1));
        assertTrue(memory.publish(session, criticalSnapshot(), null, 34, 10));
        assertTrue(memory.publish(session, normalSnapshot(), null, 34, 30_010));
        ExoMemoryPressurePolicy.Decision oneSample = coordinator.currentDecision(session);
        assertEquals(1, oneSample.normalSamples());

        assertTrue(coordinator.publishBaseline(
                session, baseline(), 0, fallback(), store.snapshot().device(), 45_000));

        ExoMemoryPressurePolicy.Decision repeated = coordinator.currentDecision(session);
        assertEquals(1, repeated.normalSamples());
        assertEquals(oneSample.effectiveTargetBytes(), repeated.effectiveTargetBytes());
        coordinator.close();
    }

    @Test
    public void replacementSessionRejectsOldMemoryAndTraceReads() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        PlaybackMemoryCoordinator memory = new PlaybackMemoryCoordinator(store);
        ExoMemoryPressureCoordinator coordinator = new ExoMemoryPressureCoordinator(store, memory);
        PlaybackAutoContext.SessionToken first = store.beginSession("p-em4a-1", 0);
        assertTrue(memory.beginSession(first));
        assertTrue(coordinator.publishBaseline(
                first, baseline(), 0, fallback(), PlaybackAutoContext.DeviceFacts.unknown(), 1));

        PlaybackAutoContext.SessionToken second = store.beginSession("p-em4b-1", 20);
        assertTrue(memory.beginSession(second));
        assertFalse(memory.publish(first, criticalSnapshot(), null, 34, 30));
        assertNull(coordinator.currentDecision(first));
        assertNull(coordinator.currentDecision("p-em4a-1"));
        assertTrue(coordinator.publishBaseline(
                second, baseline(), 0, fallback(), PlaybackAutoContext.DeviceFacts.unknown(), 31));
        assertEquals(mib(96), coordinator.currentDecision(second).effectiveTargetBytes());
        coordinator.close();
    }

    @Test
    public void listenerFailuresAreIsolatedAndRegistrationsClose() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        PlaybackMemoryCoordinator memory = new PlaybackMemoryCoordinator(store);
        ExoMemoryPressureCoordinator coordinator = new ExoMemoryPressureCoordinator(store, memory);
        PlaybackAutoContext.SessionToken session = store.beginSession("p-em5-1", 0);
        assertTrue(memory.beginSession(session));
        AtomicInteger delivered = new AtomicInteger();
        ExoMemoryPressureCoordinator.Registration failing = coordinator.addListener(update -> {
            throw new IllegalStateException("listener failure");
        });
        ExoMemoryPressureCoordinator.Registration healthy =
                coordinator.addListener(update -> delivered.incrementAndGet());

        assertTrue(coordinator.publishBaseline(
                session, baseline(), 0, fallback(), PlaybackAutoContext.DeviceFacts.unknown(), 1));
        assertEquals(1, delivered.get());
        assertEquals(2, coordinator.listenerCount());

        failing.close();
        healthy.close();
        healthy.close();
        assertEquals(0, coordinator.listenerCount());
        coordinator.close();
    }

    private static ExoTargetBufferPolicy.Decision baseline() {
        ExoTargetBufferPolicy.MediaDemand demand = new ExoTargetBufferPolicy.MediaDemand(
                20_000_000L,
                ExoTargetBufferPolicy.DemandSource.SELECTED_TRACK,
                PlaybackAutoContext.Confidence.MEDIUM,
                20_000_000L,
                ExoTargetBufferPolicy.DemandSource.SELECTED_TRACK,
                PlaybackAutoContext.Confidence.MEDIUM);
        return ExoTargetBufferPolicy.resolve(
                demand,
                0,
                fallback(),
                PlaybackAutoContext.DeviceFacts.unknown(),
                0);
    }

    private static ExoBufferBudget.Budget fallback() {
        return ExoBufferBudget.calculate(
                ExoBufferBudget.MAX_TARGET_BYTES,
                mibLong(1024),
                false);
    }

    private static PlaybackAutoContext.MemorySnapshot criticalSnapshot() {
        return new PlaybackAutoContext.MemorySnapshot(
                PlaybackAutoContext.MemoryTrigger.LOW_MEMORY,
                mibLong(400),
                mibLong(1024),
                mibLong(624),
                false,
                mibLong(2048),
                mibLong(100),
                mibLong(100),
                true,
                null,
                100,
                mibLong(32));
    }

    private static PlaybackAutoContext.MemorySnapshot normalSnapshot() {
        return new PlaybackAutoContext.MemorySnapshot(
                PlaybackAutoContext.MemoryTrigger.PERIODIC,
                mibLong(400),
                mibLong(1024),
                mibLong(624),
                false,
                mibLong(2048),
                mibLong(1024),
                mibLong(128),
                false,
                null,
                100,
                mibLong(32));
    }

    private static int mib(int value) {
        return value * 1024 * 1024;
    }

    private static long mibLong(int value) {
        return value * 1024L * 1024L;
    }
}
