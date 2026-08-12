package com.fongmi.android.tv.player;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaybackMemoryCoordinatorTest {

    @Test
    public void publishCreatesTtlFactsWithCallbackAndApiSources() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        PlaybackMemoryCoordinator coordinator = new PlaybackMemoryCoordinator(store);
        PlaybackAutoContext.SessionToken session = store.beginSession("p-memory-1", 10);
        assertTrue(coordinator.beginSession(session));

        assertTrue(coordinator.publish(session, snapshot(
                PlaybackAutoContext.MemoryTrigger.TRIM_MEMORY,
                500L,
                100L,
                false,
                PlaybackMemoryPolicy.TRIM_MEMORY_RUNNING_LOW), 12_345L, 33, 100));

        PlaybackAutoContext.DeviceFacts device = store.snapshot().device();
        assertEquals(PlaybackAutoContext.MemoryPressure.MODERATE, device.memoryPressure().value());
        assertEquals(PlaybackAutoContext.ValueSource.SYSTEM_CALLBACK, device.memoryPressure().source());
        assertEquals(PlaybackAutoContext.ValueSource.SYSTEM_CALLBACK, device.memorySnapshot().source());
        assertEquals(PlaybackAutoContext.ValueSource.SYSTEM_API, device.diagnosticPssBytes().source());
        assertEquals(Long.valueOf(12_345), device.diagnosticPssBytes().value());
        assertTrue(device.memoryPressure().isUsable(100 + PlaybackMemoryPolicy.PRESSURE_TTL_MS - 1));
        assertFalse(device.memoryPressure().isUsable(100 + PlaybackMemoryPolicy.PRESSURE_TTL_MS));
        assertTrue(device.diagnosticPssBytes().isUsable(100 + PlaybackMemoryPolicy.PSS_TTL_MS - 1));
        assertFalse(device.diagnosticPssBytes().isUsable(100 + PlaybackMemoryPolicy.PSS_TTL_MS));
    }

    @Test
    public void pssCanPublishWithoutEnoughPressureEvidence() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        PlaybackMemoryCoordinator coordinator = new PlaybackMemoryCoordinator(store);
        PlaybackAutoContext.SessionToken session = store.beginSession("p-memory-2", 20);
        assertTrue(coordinator.beginSession(session));

        assertTrue(coordinator.publish(session, null, 4_096L, 34, 30));

        PlaybackAutoContext.DeviceFacts device = store.snapshot().device();
        assertFalse(device.memoryPressure().hasValue());
        assertFalse(device.memorySnapshot().hasValue());
        assertEquals(Long.valueOf(4_096), device.diagnosticPssBytes().value());
        assertEquals(1, store.snapshot().revision());
    }

    @Test
    public void staleAndEndedSessionsCannotPublish() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        PlaybackMemoryCoordinator coordinator = new PlaybackMemoryCoordinator(store);
        PlaybackAutoContext.SessionToken first = store.beginSession("p-memory-3", 30);
        assertTrue(coordinator.beginSession(first));
        assertTrue(coordinator.endSession(first));
        assertFalse(coordinator.publish(first, normalSnapshot(), null, 34, 31));

        PlaybackAutoContext.SessionToken second = store.beginSession("p-memory-4", 40);
        assertFalse(coordinator.beginSession(first));
        assertTrue(coordinator.beginSession(second));
        assertFalse(coordinator.publish(first, normalSnapshot(), null, 34, 41));
        assertTrue(coordinator.publish(second, normalSnapshot(), null, 34, 42));
        assertEquals(second, store.snapshot().session());
        assertEquals(1, store.snapshot().revision());
    }

    @Test
    public void listenerFailuresAreIsolatedAndRegistrationsUnbind() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        PlaybackMemoryCoordinator coordinator = new PlaybackMemoryCoordinator(store);
        PlaybackAutoContext.SessionToken session = store.beginSession("p-memory-5", 50);
        assertTrue(coordinator.beginSession(session));
        AtomicInteger delivered = new AtomicInteger();
        PlaybackMemoryCoordinator.Registration failing = coordinator.addListener(update -> {
            throw new IllegalStateException("listener failure");
        });
        PlaybackMemoryCoordinator.Registration healthy = coordinator.addListener(update -> delivered.incrementAndGet());

        assertTrue(coordinator.publish(session, normalSnapshot(), null, 34, 51));
        assertEquals(1, delivered.get());
        assertEquals(2, coordinator.listenerCount());

        failing.close();
        healthy.close();
        healthy.close();
        assertEquals(0, coordinator.listenerCount());
        assertTrue(coordinator.publish(session, normalSnapshot(), null, 34, 52));
        assertEquals(1, delivered.get());
    }

    @Test
    public void concurrentPublicationsShareOneAtomicSession() throws Exception {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        PlaybackMemoryCoordinator coordinator = new PlaybackMemoryCoordinator(store);
        PlaybackAutoContext.SessionToken session = store.beginSession("p-memory-6", 60);
        assertTrue(coordinator.beginSession(session));
        int publications = 160;
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger delivered = new AtomicInteger();
        coordinator.addListener(update -> delivered.incrementAndGet());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < publications; i++) {
            int index = i;
            futures.add(executor.submit(() -> {
                await(start);
                if (coordinator.publish(session, normalSnapshot(), (long) index, 34, 100 + index)) {
                    accepted.incrementAndGet();
                }
            }));
        }
        start.countDown();
        for (Future<?> future : futures) future.get();
        executor.shutdownNow();

        assertEquals(publications, accepted.get());
        assertEquals(publications, delivered.get());
        assertEquals(publications, store.snapshot().revision());
        assertEquals(session, store.snapshot().session());
    }

    @Test
    public void emptyPublicationIsRejectedWithoutRevision() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        PlaybackMemoryCoordinator coordinator = new PlaybackMemoryCoordinator(store);
        PlaybackAutoContext.SessionToken session = store.beginSession("p-memory-7", 70);
        assertTrue(coordinator.beginSession(session));

        assertFalse(coordinator.publish(session, PlaybackAutoContext.MemorySnapshot.unknown(), -1L, 34, 71));
        assertEquals(0, store.snapshot().revision());
    }

    private static PlaybackAutoContext.MemorySnapshot normalSnapshot() {
        return snapshot(PlaybackAutoContext.MemoryTrigger.PERIODIC, 500L, 100L, false, null);
    }

    private static PlaybackAutoContext.MemorySnapshot snapshot(
            PlaybackAutoContext.MemoryTrigger trigger,
            Long available,
            Long threshold,
            Boolean lowMemory,
            Integer trimLevel) {
        return new PlaybackAutoContext.MemorySnapshot(
                trigger,
                50L,
                100L,
                50L,
                false,
                2_000L,
                available,
                threshold,
                lowMemory,
                trimLevel,
                100,
                10L);
    }

    private static void await(CountDownLatch start) {
        try {
            start.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }
}
