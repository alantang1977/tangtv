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

public class PlaybackAutoContextStoreTest {

    @Test
    public void newSessionAtomicallyReplacesFactsAndRejectsOldUpdates() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        PlaybackAutoContext.SessionToken first = store.beginSession("p-abc-1", 100);
        PlaybackAutoContext.Fact<PlaybackAutoContext.Kernel> mpv = kernel(PlaybackAutoContext.Kernel.MPV, 110);
        assertTrue(store.publishPlaybackFacts(first, mpv, decode(true, 110), path("https://cdn.example.com/a"), 110));
        PlaybackAutoContext before = store.snapshot();

        PlaybackAutoContext.SessionToken second = store.beginSession("p-abc-2", 200);
        PlaybackAutoContext after = store.snapshot();

        assertTrue(second.active());
        assertTrue(second.generation() > first.generation());
        assertEquals(first, before.session());
        assertEquals(second, after.session());
        assertEquals(PlaybackAutoContext.Kernel.UNKNOWN, after.kernel().value());
        assertEquals(0, after.revision());
        assertFalse(store.publishDeviceFacts(first, PlaybackAutoContext.DeviceFacts.unknown(), 210));
        assertFalse(store.publishResourceFacts(first,
                PlaybackResourceClassifier.classifyRequest("https://cdn.example/old.m3u8", "hls", "hls").toResourceFacts(210), 210));
        assertFalse(store.clear(first));
        assertEquals(second, store.snapshot().session());
    }

    @Test
    public void clearOnlyRemovesTheCurrentSession() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        PlaybackAutoContext.SessionToken session = store.beginSession("p-abc-3", 300);

        assertTrue(store.clear(session));
        assertFalse(store.snapshot().active());
        assertFalse(store.clear(session));
    }

    @Test
    public void publishingCreatesANewImmutableSnapshot() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        PlaybackAutoContext.SessionToken session = store.beginSession("p-abc-immutable", 350);
        PlaybackAutoContext before = store.snapshot();

        assertTrue(store.publishPathFacts(session, path("https://cdn.example.com/a"), 360));
        PlaybackAutoContext after = store.snapshot();

        assertEquals(0, before.revision());
        assertEquals(PlaybackRoute.OTHER, before.path().route().value());
        assertEquals(1, after.revision());
        assertEquals(PlaybackRoute.DIRECT_REMOTE_HTTP, after.path().route().value());
    }

    @Test
    public void playbackAndResourceFactsPublishInOneRevision() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        PlaybackAutoContext.SessionToken session = store.beginSession("p-resource-atomic", 370);
        PlaybackResourceClassifier.Classification classification = PlaybackResourceClassifier.classifyHls(
                "https://cdn.example/live.m3u8", null, "#EXTM3U\n#EXTINF:2,\na.ts\n");

        assertTrue(store.publishPlaybackFacts(session, kernel(PlaybackAutoContext.Kernel.EXO, 380), decode(true, 380),
                classification.toResourceFacts(380), classification.toPathFacts(PlaybackRoute.resolve("https://cdn.example/live.m3u8"), 380), 380));

        PlaybackAutoContext snapshot = store.snapshot();
        assertEquals(1, snapshot.revision());
        assertEquals(PlaybackAutoContext.Protocol.HLS, snapshot.resource().protocol().value());
        assertEquals(PlaybackAutoContext.StreamKind.LIVE, snapshot.resource().streamKind().value());
    }

    @Test
    public void selectiveMemoryPublicationPreservesOtherDeviceFacts() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        PlaybackAutoContext.SessionToken session = store.beginSession("p-memory-selective", 390);
        PlaybackAutoContext.Fact<PlaybackAutoContext.ThermalState> thermal = PlaybackAutoContext.Fact.withTtl(
                PlaybackAutoContext.ThermalState.MODERATE, PlaybackAutoContext.ValueSource.SYSTEM_API,
                PlaybackAutoContext.Confidence.HIGH, 391, 60_000);
        PlaybackAutoContext.Fact<PlaybackAutoContext.PowerState> power = PlaybackAutoContext.Fact.withTtl(
                PlaybackAutoContext.PowerState.POWER_SAVE, PlaybackAutoContext.ValueSource.SYSTEM_CALLBACK,
                PlaybackAutoContext.Confidence.HIGH, 391, 60_000);
        PlaybackAutoContext.Fact<PlaybackAutoContext.NetworkCost> network = PlaybackAutoContext.Fact.withTtl(
                PlaybackAutoContext.NetworkCost.METERED, PlaybackAutoContext.ValueSource.SYSTEM_CALLBACK,
                PlaybackAutoContext.Confidence.HIGH, 391, 60_000);
        assertTrue(store.publishDeviceFacts(session, new PlaybackAutoContext.DeviceFacts(
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.MemoryPressure.UNKNOWN),
                thermal, power, network), 391));

        PlaybackAutoContext.MemorySnapshot memorySnapshot = new PlaybackAutoContext.MemorySnapshot(
                PlaybackAutoContext.MemoryTrigger.PERIODIC,
                50L, 100L, 50L, false, 2_000L, 1_000L, 100L, false, null, 100, 10L);
        PlaybackAutoContext.Fact<PlaybackAutoContext.MemoryPressure> pressure = PlaybackAutoContext.Fact.withTtl(
                PlaybackAutoContext.MemoryPressure.NORMAL, PlaybackAutoContext.ValueSource.SYSTEM_API,
                PlaybackAutoContext.Confidence.HIGH, 392, 60_000);
        PlaybackAutoContext.Fact<PlaybackAutoContext.MemorySnapshot> snapshotFact = PlaybackAutoContext.Fact.withTtl(
                memorySnapshot, PlaybackAutoContext.ValueSource.SYSTEM_API,
                PlaybackAutoContext.Confidence.HIGH, 392, 60_000);
        assertTrue(store.publishMemoryFacts(session, pressure, snapshotFact, null, 392));
        assertTrue(store.publishMemoryFacts(session, null, null, PlaybackAutoContext.Fact.withTtl(
                4_096L, PlaybackAutoContext.ValueSource.SYSTEM_API,
                PlaybackAutoContext.Confidence.LOW, 393, 600_000), 393));

        PlaybackAutoContext.DeviceFacts device = store.snapshot().device();
        assertEquals(thermal, device.thermalState());
        assertEquals(power, device.powerState());
        assertEquals(network, device.networkCost());
        assertEquals(pressure, device.memoryPressure());
        assertEquals(snapshotFact, device.memorySnapshot());
        assertEquals(Long.valueOf(4_096), device.diagnosticPssBytes().value());
        assertEquals(3, store.snapshot().revision());
        assertFalse(store.publishMemoryFacts(session, null, null, null, 394));
    }

    @Test
    public void selectiveSystemConditionPublicationPreservesMemoryFacts() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        PlaybackAutoContext.SessionToken session = store.beginSession("p-system-selective", 395);
        PlaybackAutoContext.MemorySnapshot memorySnapshot = new PlaybackAutoContext.MemorySnapshot(
                PlaybackAutoContext.MemoryTrigger.PERIODIC,
                50L, 100L, 50L, false, 2_000L, 1_000L, 100L, false, null, 100, 10L);
        PlaybackAutoContext.Fact<PlaybackAutoContext.MemoryPressure> pressure = PlaybackAutoContext.Fact.withTtl(
                PlaybackAutoContext.MemoryPressure.NORMAL, PlaybackAutoContext.ValueSource.SYSTEM_API,
                PlaybackAutoContext.Confidence.HIGH, 396, 60_000);
        PlaybackAutoContext.Fact<PlaybackAutoContext.MemorySnapshot> memory = PlaybackAutoContext.Fact.withTtl(
                memorySnapshot, PlaybackAutoContext.ValueSource.SYSTEM_API,
                PlaybackAutoContext.Confidence.HIGH, 396, 60_000);
        PlaybackAutoContext.Fact<Long> pss = PlaybackAutoContext.Fact.withTtl(
                4_096L, PlaybackAutoContext.ValueSource.SYSTEM_API,
                PlaybackAutoContext.Confidence.LOW, 396, 600_000);
        assertTrue(store.publishMemoryFacts(session, pressure, memory, pss, 396));

        PlaybackAutoContext.NetworkSnapshot networkSnapshot = new PlaybackAutoContext.NetworkSnapshot(
                true, true, false, false, PlaybackAutoContext.NetworkTransport.ETHERNET,
                PlaybackAutoContext.DataSaverState.DISABLED);
        PlaybackAutoContext.Fact<PlaybackAutoContext.NetworkSnapshot> network = PlaybackAutoContext.Fact.withTtl(
                networkSnapshot, PlaybackAutoContext.ValueSource.SYSTEM_API,
                PlaybackAutoContext.Confidence.HIGH, 397, 120_000);
        PlaybackAutoContext.Fact<PlaybackAutoContext.NetworkCost> cost = PlaybackAutoContext.Fact.withTtl(
                PlaybackAutoContext.NetworkCost.UNMETERED, PlaybackAutoContext.ValueSource.SYSTEM_API,
                PlaybackAutoContext.Confidence.HIGH, 397, 120_000);
        assertTrue(store.publishSystemConditionFacts(session, null, null, cost, network, 397));
        PlaybackAutoContext.Fact<PlaybackAutoContext.PowerState> power = PlaybackAutoContext.Fact.withTtl(
                PlaybackAutoContext.PowerState.POWER_SAVE, PlaybackAutoContext.ValueSource.SYSTEM_CALLBACK,
                PlaybackAutoContext.Confidence.HIGH, 398, 120_000);
        assertTrue(store.publishSystemConditionFacts(session, null, power, null, null, 398));

        PlaybackAutoContext.DeviceFacts device = store.snapshot().device();
        assertEquals(pressure, device.memoryPressure());
        assertEquals(memory, device.memorySnapshot());
        assertEquals(pss, device.diagnosticPssBytes());
        assertEquals(cost, device.networkCost());
        assertEquals(network, device.networkSnapshot());
        assertEquals(power, device.powerState());
        assertFalse(device.thermalState().hasValue());
        assertEquals(3, store.snapshot().revision());
        assertFalse(store.publishSystemConditionFacts(session, null, null, null, null, 399));
    }

    @Test
    public void concurrentPublishersKeepAllCategoriesAndUseMonotonicRevisions() throws Exception {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        PlaybackAutoContext.SessionToken session = store.beginSession("p-abc-4", 400);
        int iterations = 120;
        int writers = 3;
        AtomicInteger accepted = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(writers + 1);
        List<Future<?>> futures = new ArrayList<>();
        futures.add(executor.submit(() -> {
            await(start);
            for (int i = 0; i < iterations; i++) {
                if (store.publishPathFacts(session, path(i % 2 == 0 ? "https://cdn.example.com/a" : "http://127.0.0.1:7777/a"), 500 + i)) accepted.incrementAndGet();
            }
        }));
        futures.add(executor.submit(() -> {
            await(start);
            for (int i = 0; i < iterations; i++) {
                PlaybackAutoContext.RuntimeFacts runtime = new PlaybackAutoContext.RuntimeFacts(
                        PlaybackAutoContext.Fact.forSession(PlaybackAutoContext.PlaybackPhase.READY, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK, PlaybackAutoContext.Confidence.HIGH, 500 + i),
                        PlaybackAutoContext.Fact.unknown(0L),
                        PlaybackAutoContext.Fact.unknown(0L),
                        PlaybackAutoContext.Fact.unknown(0L),
                        PlaybackAutoContext.Fact.unknown(0f),
                        PlaybackAutoContext.Fact.unknown(0L),
                        PlaybackAutoContext.Fact.unknown(0));
                if (store.publishRuntimeFacts(session, runtime, 500 + i)) accepted.incrementAndGet();
            }
        }));
        futures.add(executor.submit(() -> {
            await(start);
            for (int i = 0; i < iterations; i++) {
                if (store.publishDeviceFacts(session, PlaybackAutoContext.DeviceFacts.unknown(), 500 + i)) accepted.incrementAndGet();
            }
        }));
        futures.add(executor.submit(() -> {
            await(start);
            long previousRevision = -1;
            for (int i = 0; i < iterations * 3; i++) {
                PlaybackAutoContext snapshot = store.snapshot();
                if (!session.equals(snapshot.session()) || snapshot.revision() < previousRevision) {
                    throw new AssertionError("snapshot regressed");
                }
                previousRevision = snapshot.revision();
            }
        }));
        start.countDown();
        for (Future<?> future : futures) future.get();
        executor.shutdownNow();

        PlaybackAutoContext snapshot = store.snapshot();
        assertEquals(iterations * writers, accepted.get());
        assertEquals(accepted.get(), snapshot.revision());
        assertTrue(snapshot.path().route().value() == PlaybackRoute.DIRECT_REMOTE_HTTP
                || snapshot.path().route().value() == PlaybackRoute.EXTERNAL_LOOPBACK_PROXY);
        assertEquals(PlaybackAutoContext.PlaybackPhase.READY, snapshot.runtime().phase().value());
    }

    private static PlaybackAutoContext.Fact<PlaybackAutoContext.Kernel> kernel(PlaybackAutoContext.Kernel kernel, long sampledAt) {
        return PlaybackAutoContext.Fact.forSession(kernel, PlaybackAutoContext.ValueSource.PLAYER_MANAGER, PlaybackAutoContext.Confidence.HIGH, sampledAt);
    }

    private static PlaybackAutoContext.Fact<PlaybackAutoContext.DecodeMode> decode(boolean hardware, long sampledAt) {
        return PlaybackAutoContext.Fact.forSession(hardware ? PlaybackAutoContext.DecodeMode.HARDWARE : PlaybackAutoContext.DecodeMode.SOFTWARE,
                PlaybackAutoContext.ValueSource.PLAYER_MANAGER, PlaybackAutoContext.Confidence.HIGH, sampledAt);
    }

    private static PlaybackAutoContext.PathFacts path(String url) {
        return PlaybackAutoContext.PathFacts.fromResolution(PlaybackRoute.resolve(url), 500);
    }

    private static void await(CountDownLatch start) {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
