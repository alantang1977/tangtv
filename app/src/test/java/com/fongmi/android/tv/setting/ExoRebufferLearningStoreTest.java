package com.fongmi.android.tv.setting;

import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.PlaybackNetworkIdentityPolicy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExoRebufferLearningStoreTest {

    private static final long NOW = 1_800_000_000_000L;

    @Test
    public void missingKeyUsesDefaultWithoutWriting() {
        MemoryBackend backend = new MemoryBackend();
        ExoRebufferLearningStore store = new ExoRebufferLearningStore(backend);

        ExoRebufferLearningStore.Lookup lookup = store.lookup(key(1), NOW);

        assertFalse(lookup.hit());
        assertEquals(AutoRebufferPolicy.DEFAULT_REBUFFER_MS, lookup.rebufferMs());
        assertEquals("", backend.value);
    }

    @Test
    public void differentNetworksAndResourceDimensionsStayIsolated() {
        MemoryBackend backend = new MemoryBackend();
        ExoRebufferLearningStore store = new ExoRebufferLearningStore(backend);
        ExoRebufferLearningKey.Key first = key(1);
        ExoRebufferLearningKey.Key second = key(2);

        recordCritical(store, first, NOW);

        assertEquals(15_000, store.lookup(first, NOW).rebufferMs());
        assertFalse(store.lookup(second, NOW).hit());
        assertFalse(store.lookup(key(
                1,
                PlaybackAutoContext.Protocol.DASH,
                PlaybackAutoContext.StreamKind.VOD), NOW).hit());
        assertFalse(store.lookup(key(
                1,
                PlaybackAutoContext.Protocol.HLS,
                PlaybackAutoContext.StreamKind.LIVE), NOW).hit());
    }

    @Test
    public void entryExpiresAtTtlBoundary() {
        MemoryBackend backend = new MemoryBackend();
        ExoRebufferLearningStore store = new ExoRebufferLearningStore(backend);
        ExoRebufferLearningKey.Key key = key(1);
        recordCritical(store, key, NOW);

        assertTrue(store.lookup(
                key,
                NOW + ExoRebufferLearningStore.ENTRY_TTL_MS - 1).hit());
        assertFalse(store.lookup(
                key,
                NOW + ExoRebufferLearningStore.ENTRY_TTL_MS).hit());
        assertEquals("", backend.value);
    }

    @Test
    public void incompatibleStoreOrEntryVersionIsDiscarded() {
        MemoryBackend incompatibleStore = new MemoryBackend();
        incompatibleStore.value = "store-version=99\ninvalid";
        ExoRebufferLearningStore first =
                new ExoRebufferLearningStore(incompatibleStore);

        assertFalse(first.lookup(key(1), NOW).hit());
        assertEquals("", incompatibleStore.value);

        MemoryBackend incompatibleEntry = new MemoryBackend();
        incompatibleEntry.value = line(2, key(1), 8_000, 0, 1, "HIGH", NOW);
        ExoRebufferLearningStore second =
                new ExoRebufferLearningStore(incompatibleEntry);

        assertFalse(second.lookup(key(1), NOW).hit());
        assertEquals("", incompatibleEntry.value);
    }

    @Test
    public void corruptRowsAreRemovedWithoutLosingValidRows() {
        MemoryBackend backend = new MemoryBackend();
        ExoRebufferLearningKey.Key key = key(1);
        backend.value = line(1, key, 8_000, 0, 1, "HIGH", NOW)
                + "\nthis-is-not-a-valid-entry";
        ExoRebufferLearningStore store = new ExoRebufferLearningStore(backend);

        assertEquals(8_000, store.lookup(key, NOW).rebufferMs());
        assertFalse(backend.value.contains("this-is-not-a-valid-entry"));
    }

    @Test
    public void oldestEntriesAreEvictedAtCapacity() {
        MemoryBackend backend = new MemoryBackend();
        ExoRebufferLearningStore store = new ExoRebufferLearningStore(backend);
        for (int index = 1; index <= ExoRebufferLearningStore.MAX_ENTRIES + 1; index++) {
            recordCritical(store, key(index), NOW + index);
        }

        assertEquals(
                ExoRebufferLearningStore.MAX_ENTRIES,
                store.entryCount(NOW + ExoRebufferLearningStore.MAX_ENTRIES + 1));
        assertFalse(store.lookup(key(1), NOW + 100).hit());
        assertTrue(store.lookup(
                key(ExoRebufferLearningStore.MAX_ENTRIES + 1),
                NOW + 100).hit());
    }

    @Test
    public void cleanSessionsLowerOnlyTheirOwnEntry() {
        MemoryBackend backend = new MemoryBackend();
        ExoRebufferLearningStore store = new ExoRebufferLearningStore(backend);
        ExoRebufferLearningKey.Key key = key(1);
        recordCritical(store, key, NOW);

        store.record(key, 0, 0, 300_000, 10_000_000, 25_000_000, NOW + 1);
        ExoRebufferLearningStore.Lookup firstClean = store.lookup(key, NOW + 1);
        assertEquals(15_000, firstClean.rebufferMs());
        assertEquals(1, firstClean.cleanStreak());

        store.record(key, 0, 0, 300_000, 10_000_000, 25_000_000, NOW + 2);
        ExoRebufferLearningStore.Lookup secondClean = store.lookup(key, NOW + 2);
        assertEquals(8_000, secondClean.rebufferMs());
        assertEquals(0, secondClean.cleanStreak());
        assertEquals(3, secondClean.sampleCount());
    }

    @Test
    public void farFutureTimestampIsRejectedConservatively() {
        MemoryBackend backend = new MemoryBackend();
        ExoRebufferLearningKey.Key key = key(1);
        backend.value = line(
                1,
                key,
                15_000,
                0,
                1,
                "CRITICAL",
                NOW + ExoRebufferLearningStore.MAX_FUTURE_SKEW_MS + 1);
        ExoRebufferLearningStore store = new ExoRebufferLearningStore(backend);

        assertFalse(store.lookup(key, NOW).hit());
        assertEquals("", backend.value);
    }

    @Test
    public void persistedPayloadContainsOnlyDigestAndEnums() {
        MemoryBackend backend = new MemoryBackend();
        ExoRebufferLearningStore store = new ExoRebufferLearningStore(backend);
        ExoRebufferLearningKey.Key key = key(123456789);

        recordCritical(store, key, NOW);

        assertTrue(backend.value.contains(key.networkDigest()));
        assertFalse(backend.value.contains("123456789"));
        assertFalse(backend.value.contains("http"));
        assertFalse(backend.value.contains("/Users/"));
    }

    private static void recordCritical(
            ExoRebufferLearningStore store,
            ExoRebufferLearningKey.Key key,
            long now) {
        store.record(
                key,
                5,
                30_000,
                300_000,
                10_000_000,
                9_000_000,
                now);
    }

    private static String line(
            int version,
            ExoRebufferLearningKey.Key key,
            int rebufferMs,
            int cleanStreak,
            int sampleCount,
            String severity,
            long updatedAt) {
        return "store-version=1\n"
                + version + "|"
                + key.networkDigest() + "|"
                + key.pathKind().name() + "|"
                + key.protocol().name() + "|"
                + key.streamKind().name() + "|"
                + rebufferMs + "|"
                + cleanStreak + "|"
                + sampleCount + "|"
                + severity + "|"
                + updatedAt;
    }

    private static ExoRebufferLearningKey.Key key(int networkHandle) {
        return key(
                networkHandle,
                PlaybackAutoContext.Protocol.HLS,
                PlaybackAutoContext.StreamKind.VOD);
    }

    private static ExoRebufferLearningKey.Key key(
            int networkHandle,
            PlaybackAutoContext.Protocol protocol,
            PlaybackAutoContext.StreamKind streamKind) {
        return new ExoRebufferLearningKey.Key(
                PlaybackNetworkIdentityPolicy.digest(networkHandle),
                PlaybackAutoContext.PathKind.REMOTE,
                protocol,
                streamKind);
    }

    private static final class MemoryBackend
            implements ExoRebufferLearningStore.Backend {

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
