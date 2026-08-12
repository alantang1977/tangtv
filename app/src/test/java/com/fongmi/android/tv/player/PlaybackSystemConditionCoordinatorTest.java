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

public class PlaybackSystemConditionCoordinatorTest {

    @Test
    public void initialSamplePublishesApiFactsWithTtl() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        PlaybackSystemConditionCoordinator coordinator = new PlaybackSystemConditionCoordinator(store);
        PlaybackAutoContext.SessionToken session = store.beginSession("p-sys1-1", 10);
        assertTrue(coordinator.beginSession(session));

        assertTrue(coordinator.publish(
                session,
                PlaybackAutoContext.SystemConditionTrigger.SESSION_START,
                network(true, true, true, false),
                false,
                PlaybackSystemConditionPolicy.THERMAL_MODERATE,
                34,
                100));

        PlaybackAutoContext.DeviceFacts device = store.snapshot().device();
        assertEquals(PlaybackAutoContext.ThermalState.MODERATE, device.thermalState().value());
        assertEquals(PlaybackAutoContext.PowerState.NORMAL, device.powerState().value());
        assertEquals(PlaybackAutoContext.NetworkCost.METERED, device.networkCost().value());
        assertTrue(device.networkSnapshot().hasValue());
        assertEquals(PlaybackAutoContext.ValueSource.SYSTEM_API, device.thermalState().source());
        assertEquals(PlaybackAutoContext.ValueSource.SYSTEM_API, device.powerState().source());
        assertEquals(PlaybackAutoContext.ValueSource.SYSTEM_API, device.networkCost().source());
        assertTrue(device.networkCost().isUsable(
                100 + PlaybackSystemConditionPolicy.FACT_TTL_MS - 1));
        assertFalse(device.networkCost().isUsable(
                100 + PlaybackSystemConditionPolicy.FACT_TTL_MS));
    }

    @Test
    public void callbackSourcesOnlyApplyToTheirOwnCategory() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        PlaybackSystemConditionCoordinator coordinator = new PlaybackSystemConditionCoordinator(store);
        PlaybackAutoContext.SessionToken session = store.beginSession("p-sys2-1", 20);
        assertTrue(coordinator.beginSession(session));

        assertTrue(coordinator.publish(session,
                PlaybackAutoContext.SystemConditionTrigger.NETWORK_CALLBACK,
                network(true, true, true, true), false,
                PlaybackSystemConditionPolicy.THERMAL_NONE, 34, 21));
        PlaybackAutoContext.DeviceFacts networkCallback = store.snapshot().device();
        assertEquals(PlaybackAutoContext.ValueSource.SYSTEM_CALLBACK,
                networkCallback.networkCost().source());
        assertEquals(PlaybackAutoContext.ValueSource.SYSTEM_CALLBACK,
                networkCallback.networkSnapshot().source());
        assertEquals(PlaybackAutoContext.ValueSource.SYSTEM_API,
                networkCallback.powerState().source());
        assertEquals(PlaybackAutoContext.ValueSource.SYSTEM_API,
                networkCallback.thermalState().source());

        assertTrue(coordinator.publish(session,
                PlaybackAutoContext.SystemConditionTrigger.POWER_CALLBACK,
                null, true, null, 34, 22));
        assertEquals(PlaybackAutoContext.ValueSource.SYSTEM_CALLBACK,
                store.snapshot().device().powerState().source());
        assertEquals(PlaybackAutoContext.PowerState.POWER_SAVE,
                store.snapshot().device().powerState().value());

        assertTrue(coordinator.publish(session,
                PlaybackAutoContext.SystemConditionTrigger.THERMAL_CALLBACK,
                null, null, PlaybackSystemConditionPolicy.THERMAL_SEVERE, 34, 23));
        assertEquals(PlaybackAutoContext.ValueSource.SYSTEM_CALLBACK,
                store.snapshot().device().thermalState().source());
        assertEquals(PlaybackAutoContext.ThermalState.SEVERE,
                store.snapshot().device().thermalState().value());
    }

    @Test
    public void preApi29ThermalStaysUnknownWhileOtherFactsPublish() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        PlaybackSystemConditionCoordinator coordinator = new PlaybackSystemConditionCoordinator(store);
        PlaybackAutoContext.SessionToken session = store.beginSession("p-sys3-1", 30);
        assertTrue(coordinator.beginSession(session));

        assertTrue(coordinator.publish(session,
                PlaybackAutoContext.SystemConditionTrigger.SESSION_START,
                network(true, true, false, false), false,
                PlaybackSystemConditionPolicy.THERMAL_SEVERE, 28, 31));

        PlaybackAutoContext.DeviceFacts device = store.snapshot().device();
        assertFalse(device.thermalState().hasValue());
        assertEquals(PlaybackAutoContext.PowerState.NORMAL, device.powerState().value());
        assertEquals(PlaybackAutoContext.NetworkCost.UNMETERED, device.networkCost().value());
    }

    @Test
    public void staleAndEndedSessionsCannotPublish() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        PlaybackSystemConditionCoordinator coordinator = new PlaybackSystemConditionCoordinator(store);
        PlaybackAutoContext.SessionToken first = store.beginSession("p-sys4-1", 40);
        assertTrue(coordinator.beginSession(first));
        assertTrue(coordinator.endSession(first));
        assertFalse(coordinator.publish(first,
                PlaybackAutoContext.SystemConditionTrigger.PERIODIC,
                network(true, true, false, false), false, null, 34, 41));

        PlaybackAutoContext.SessionToken second = store.beginSession("p-sys4-2", 42);
        assertFalse(coordinator.beginSession(first));
        assertTrue(coordinator.beginSession(second));
        assertFalse(coordinator.publish(first,
                PlaybackAutoContext.SystemConditionTrigger.NETWORK_CALLBACK,
                network(true, true, true, false), false, null, 34, 43));
        assertTrue(coordinator.publish(second,
                PlaybackAutoContext.SystemConditionTrigger.PERIODIC,
                network(true, true, false, false), false, null, 34, 44));
        assertEquals(second, store.snapshot().session());
        assertEquals(1, store.snapshot().revision());
    }

    @Test
    public void listenerFailuresAreIsolatedAndRegistrationsUnbind() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        PlaybackSystemConditionCoordinator coordinator = new PlaybackSystemConditionCoordinator(store);
        PlaybackAutoContext.SessionToken session = store.beginSession("p-sys5-1", 50);
        assertTrue(coordinator.beginSession(session));
        AtomicInteger delivered = new AtomicInteger();
        PlaybackSystemConditionCoordinator.Registration failing = coordinator.addListener(update -> {
            throw new IllegalStateException("listener failure");
        });
        PlaybackSystemConditionCoordinator.Registration healthy =
                coordinator.addListener(update -> delivered.incrementAndGet());

        assertTrue(coordinator.publish(session,
                PlaybackAutoContext.SystemConditionTrigger.PERIODIC,
                network(true, true, false, false), false, null, 34, 51));
        assertEquals(1, delivered.get());
        assertEquals(2, coordinator.listenerCount());

        failing.close();
        healthy.close();
        healthy.close();
        assertEquals(0, coordinator.listenerCount());
        assertTrue(coordinator.publish(session,
                PlaybackAutoContext.SystemConditionTrigger.PERIODIC,
                network(true, true, true, false), false, null, 34, 52));
        assertEquals(1, delivered.get());
    }

    @Test
    public void concurrentPublicationsRemainInOneAtomicSession() throws Exception {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        PlaybackSystemConditionCoordinator coordinator = new PlaybackSystemConditionCoordinator(store);
        PlaybackAutoContext.SessionToken session = store.beginSession("p-sys6-1", 60);
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
                if (coordinator.publish(session,
                        PlaybackAutoContext.SystemConditionTrigger.NETWORK_CALLBACK,
                        network(true, true, index % 2 == 0, false),
                        index % 3 == 0,
                        PlaybackSystemConditionPolicy.THERMAL_NONE,
                        34,
                        100 + index)) {
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
        PlaybackSystemConditionCoordinator coordinator = new PlaybackSystemConditionCoordinator(store);
        PlaybackAutoContext.SessionToken session = store.beginSession("p-sys7-1", 70);
        assertTrue(coordinator.beginSession(session));

        assertFalse(coordinator.publish(session,
                PlaybackAutoContext.SystemConditionTrigger.UNKNOWN,
                PlaybackAutoContext.NetworkSnapshot.unknown(), null, null, 34, 71));
        assertEquals(0, store.snapshot().revision());
    }

    private static PlaybackAutoContext.NetworkSnapshot network(
            Boolean available, Boolean validated, Boolean metered, Boolean roaming) {
        return new PlaybackAutoContext.NetworkSnapshot(
                available,
                validated,
                metered,
                roaming,
                PlaybackAutoContext.NetworkTransport.CELLULAR,
                PlaybackAutoContext.DataSaverState.DISABLED);
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
