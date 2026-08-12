package com.fongmi.android.tv.player.exo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ExoDecoderRuntimeProfileStoreTest {

    private static final long NOW = 1_800_000_000_000L;

    @Test
    public void oneFatalFailureDoesNotBlacklistButSecondDoes() {
        MemoryBackend backend = new MemoryBackend();
        ExoDecoderRuntimeProfileStore store = new ExoDecoderRuntimeProfileStore(backend);
        ExoDecoderRuntimeKey.Key key = key(1, 1);

        ExoDecoderRuntimeProfileStore.Entry first = store.recordFailure(
                key,
                ExoDecoderRuntimeProfileStore.FailureKind.INIT,
                metrics(3, 1_000, 0),
                NOW).entry();
        assertNotNull(first);
        assertFalse(first.blacklisted());

        ExoDecoderRuntimeProfileStore.Entry second = store.recordFailure(
                key,
                ExoDecoderRuntimeProfileStore.FailureKind.DECODE,
                metrics(4, 2_000, 1),
                NOW + 1).entry();
        assertTrue(second.blacklisted());
        assertEquals(2, second.consecutiveFailures());
        assertEquals(7, second.droppedFrames());
        assertEquals(1, second.recoverableCodecErrors());
    }

    @Test
    public void stableSuccessClearsConsecutiveBlacklistWithoutErasingHistory() {
        ExoDecoderRuntimeProfileStore store = new ExoDecoderRuntimeProfileStore(new MemoryBackend());
        ExoDecoderRuntimeKey.Key key = key(1, 1);
        failTwice(store, key);

        ExoDecoderRuntimeProfileStore.Entry recovered = store.recordStableSuccess(
                key, metrics(1, 30_000, 0), NOW + 2).entry();

        assertFalse(recovered.blacklisted());
        assertEquals(0, recovered.consecutiveFailures());
        assertEquals(2, recovered.failureCount());
        assertEquals(1, recovered.successCount());
    }

    @Test
    public void firstFrameIsCountedWithoutClearingFailureStreak() {
        ExoDecoderRuntimeProfileStore store =
                new ExoDecoderRuntimeProfileStore(new MemoryBackend());
        ExoDecoderRuntimeKey.Key key = key(1, 1);
        store.recordFailure(
                key,
                ExoDecoderRuntimeProfileStore.FailureKind.DECODE,
                ExoDecoderRuntimeProfileStore.Metrics.EMPTY,
                NOW);

        ExoDecoderRuntimeProfileStore.Entry entry =
                store.recordFirstFrame(key, NOW + 1).entry();

        assertEquals(1, entry.firstFrameCount());
        assertEquals(1, entry.consecutiveFailures());
        assertEquals(0, entry.successCount());
        assertFalse(entry.blacklisted());
    }

    @Test
    public void dropsAndRecoverableErrorsNeverBlacklistByThemselves() {
        ExoDecoderRuntimeProfileStore store = new ExoDecoderRuntimeProfileStore(new MemoryBackend());
        ExoDecoderRuntimeKey.Key key = key(1, 1);

        ExoDecoderRuntimeProfileStore.Entry entry = store.recordObservation(
                key, metrics(500, 60_000, 9), NOW).entry();

        assertNotNull(entry);
        assertFalse(entry.blacklisted());
        assertEquals(0, entry.failureCount());
        assertEquals(500, entry.droppedFrames());
        assertEquals(9, entry.recoverableCodecErrors());
    }

    @Test
    public void fallbackResultsAreCountedWithoutChangingBlacklist() {
        ExoDecoderRuntimeProfileStore store = new ExoDecoderRuntimeProfileStore(new MemoryBackend());
        ExoDecoderRuntimeKey.Key key = key(1, 1);
        failTwice(store, key);

        store.recordFallback(key, ExoDecoderRuntimeProfileStore.FallbackResult.SUCCESS, NOW + 2);
        ExoDecoderRuntimeProfileStore.Entry entry = store.recordFallback(
                key, ExoDecoderRuntimeProfileStore.FallbackResult.FAILURE, NOW + 3).entry();

        assertTrue(entry.blacklisted());
        assertEquals(1, entry.fallbackSuccessCount());
        assertEquals(1, entry.fallbackFailureCount());
    }

    @Test
    public void environmentVersionChangeDoesNotReuseBlacklist() {
        ExoDecoderRuntimeProfileStore store = new ExoDecoderRuntimeProfileStore(new MemoryBackend());
        failTwice(store, key(1, 1));

        assertTrue(store.lookup(key(1, 1), NOW + 2).blacklisted());
        assertFalse(store.lookup(key(2, 1), NOW + 2).hit());
    }

    @Test
    public void entryExpiresAtTtlBoundary() {
        MemoryBackend backend = new MemoryBackend();
        ExoDecoderRuntimeProfileStore store = new ExoDecoderRuntimeProfileStore(backend);
        ExoDecoderRuntimeKey.Key key = key(1, 1);
        failTwice(store, key);

        assertTrue(store.lookup(
                key,
                NOW + 1 + ExoDecoderRuntimeProfileStore.ENTRY_TTL_MS - 1).hit());
        assertFalse(store.lookup(
                key,
                NOW + 1 + ExoDecoderRuntimeProfileStore.ENTRY_TTL_MS).hit());
        assertEquals("", backend.value);
    }

    @Test
    public void corruptStoreIsClearedSafely() {
        MemoryBackend backend = new MemoryBackend();
        backend.value = "store-version=99\nlocal/path/http://secret";
        ExoDecoderRuntimeProfileStore store = new ExoDecoderRuntimeProfileStore(backend);

        assertFalse(store.lookup(key(1, 1), NOW).hit());
        assertEquals("", backend.value);
    }

    @Test
    public void persistedBlacklistSurvivesStoreRecreation() {
        MemoryBackend backend = new MemoryBackend();
        ExoDecoderRuntimeKey.Key key = key(1, 1);
        failTwice(new ExoDecoderRuntimeProfileStore(backend), key);

        ExoDecoderRuntimeProfileStore restored =
                new ExoDecoderRuntimeProfileStore(backend);

        ExoDecoderRuntimeProfileStore.Lookup lookup = restored.lookup(key, NOW + 2);
        assertTrue(lookup.hit());
        assertTrue(lookup.blacklisted());
        assertEquals(2, lookup.entry().failureCount());
    }

    @Test
    public void incompatibleEntryVersionIsDroppedAndRewritten() {
        MemoryBackend backend = new MemoryBackend();
        ExoDecoderRuntimeKey.Key key = key(1, 1);
        failTwice(new ExoDecoderRuntimeProfileStore(backend), key);
        backend.value = backend.value.replaceFirst("\\n1\\|", "\n99|");

        ExoDecoderRuntimeProfileStore restored =
                new ExoDecoderRuntimeProfileStore(backend);

        assertFalse(restored.lookup(key, NOW + 2).hit());
        assertEquals("", backend.value);
    }

    @Test
    public void entryTooFarInFutureIsDropped() {
        MemoryBackend backend = new MemoryBackend();
        ExoDecoderRuntimeKey.Key key = key(1, 1);
        new ExoDecoderRuntimeProfileStore(backend).recordFailure(
                key,
                ExoDecoderRuntimeProfileStore.FailureKind.INIT,
                ExoDecoderRuntimeProfileStore.Metrics.EMPTY,
                NOW + ExoDecoderRuntimeProfileStore.MAX_FUTURE_SKEW_MS + 1);

        ExoDecoderRuntimeProfileStore restored =
                new ExoDecoderRuntimeProfileStore(backend);

        assertFalse(restored.lookup(key, NOW).hit());
        assertEquals("", backend.value);
    }

    @Test
    public void capacityPrefersKeepingBlacklistedEntries() {
        ExoDecoderRuntimeProfileStore store = new ExoDecoderRuntimeProfileStore(new MemoryBackend());
        ExoDecoderRuntimeKey.Key protectedKey = key(1, 1);
        failTwice(store, protectedKey);
        for (int index = 2; index <= ExoDecoderRuntimeProfileStore.MAX_ENTRIES + 1; index++) {
            store.recordObservation(key(1, index), metrics(1, 100, 0), NOW + index);
        }

        assertEquals(ExoDecoderRuntimeProfileStore.MAX_ENTRIES,
                store.entryCount(NOW + ExoDecoderRuntimeProfileStore.MAX_ENTRIES + 1));
        assertTrue(store.lookup(protectedKey, NOW + 100).blacklisted());
        assertFalse(store.lookup(key(1, 2), NOW + 100).hit());
    }

    @Test
    public void persistedPayloadContainsNoRawDeviceDecoderUrlOrErrorText() {
        MemoryBackend backend = new MemoryBackend();
        ExoDecoderRuntimeProfileStore store = new ExoDecoderRuntimeProfileStore(backend);
        store.recordFailure(
                key(1, 1),
                ExoDecoderRuntimeProfileStore.FailureKind.INIT,
                metrics(1, 100, 0),
                NOW);

        assertFalse(backend.value.contains("fingerprint"));
        assertFalse(backend.value.contains("c2.vendor"));
        assertFalse(backend.value.contains("http"));
        assertFalse(backend.value.contains("/Users/"));
        assertFalse(backend.value.contains("exception message"));
    }

    private static void failTwice(
            ExoDecoderRuntimeProfileStore store,
            ExoDecoderRuntimeKey.Key key) {
        store.recordFailure(key, ExoDecoderRuntimeProfileStore.FailureKind.INIT,
                ExoDecoderRuntimeProfileStore.Metrics.EMPTY, NOW);
        store.recordFailure(key, ExoDecoderRuntimeProfileStore.FailureKind.DECODE,
                ExoDecoderRuntimeProfileStore.Metrics.EMPTY, NOW + 1);
    }

    private static ExoDecoderRuntimeProfileStore.Metrics metrics(
            long drops,
            long duration,
            int recoverableErrors) {
        return new ExoDecoderRuntimeProfileStore.Metrics(drops, duration, recoverableErrors);
    }

    private static ExoDecoderRuntimeKey.Key key(int environment, int decoder) {
        return new ExoDecoderRuntimeKey.Key(
                digest(environment),
                digest(decoder + 1000),
                "video/hevc",
                digest(decoder + 2000),
                2,
                153,
                3840,
                2160,
                60_000,
                6,
                2,
                6,
                true,
                false,
                ExoDecoderRuntimeSession.OutputTarget.SURFACE,
                false);
    }

    private static String digest(int value) {
        return String.format(java.util.Locale.US, "%024x", value);
    }

    private static final class MemoryBackend
            implements ExoDecoderRuntimeProfileStore.Backend {

        private String value = "";

        @Override
        public String read() {
            return value;
        }

        @Override
        public void write(String value) {
            this.value = value == null ? "" : value;
        }

        @Override
        public void clear() {
            value = "";
        }
    }
}
