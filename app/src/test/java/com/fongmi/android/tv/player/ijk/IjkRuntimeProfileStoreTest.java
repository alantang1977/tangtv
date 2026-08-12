package com.fongmi.android.tv.player.ijk;

import androidx.media3.common.C;
import androidx.media3.common.MimeTypes;

import com.fongmi.android.tv.player.PlaybackAutoContext;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class IjkRuntimeProfileStoreTest {

    private static final long NOW = 1_800_000_000_000L;

    @Test
    public void oneFailureDoesNotExcludeButSecondIndependentFailureDoes() {
        IjkRuntimeProfileStore store = store();
        IjkRuntimeProfileKey.Key key = key(1, 3_840);

        IjkRuntimeProfileStore.Entry first = store.recordFailure(
                key,
                IjkRuntimeProfilePolicy.Path.IJK_HARD,
                IjkRuntimeProfilePolicy.FailureKind.DECODER,
                metrics(1, 40, 800, 10, 20),
                NOW).entry();
        IjkRuntimeProfileStore.Entry second = store.recordFailure(
                key,
                IjkRuntimeProfilePolicy.Path.IJK_HARD,
                IjkRuntimeProfilePolicy.FailureKind.RUNTIME,
                metrics(2, 60, 700, 30, 40),
                NOW + 1).entry();

        assertNotNull(first);
        assertFalse(first.excluded());
        assertTrue(second.excluded());
        assertEquals(2, second.consecutiveFailures());
        assertEquals(3, second.rebufferCount());
        assertEquals(60, second.maxDropRatePermille());
        assertEquals(700, second.minRenderedRatioPermille());
        assertEquals(30, second.maxNativeHeapGrowthBytes());
        assertEquals(40, second.maxPssGrowthBytes());
    }

    @Test
    public void stableSuccessClearsExclusionWithoutErasingHistory() {
        IjkRuntimeProfileStore store = store();
        IjkRuntimeProfileKey.Key key = key(1, 3_840);
        failTwice(store, key, IjkRuntimeProfilePolicy.Path.IJK_HARD);

        IjkRuntimeProfileStore.Entry recovered = store.recordStableSuccess(
                key,
                IjkRuntimeProfilePolicy.Path.IJK_HARD,
                metrics(0, 5, 1_000, 50, 60),
                NOW + 2).entry();

        assertFalse(recovered.excluded());
        assertTrue(recovered.verified());
        assertEquals(0, recovered.consecutiveFailures());
        assertEquals(2, recovered.failureCount());
        assertEquals(1, recovered.stableSuccessCount());
    }

    @Test
    public void firstFrameDoesNotClearFailureStreak() {
        IjkRuntimeProfileStore store = store();
        IjkRuntimeProfileKey.Key key = key(1, 3_840);
        store.recordFailure(
                key,
                IjkRuntimeProfilePolicy.Path.IJK_HARD,
                IjkRuntimeProfilePolicy.FailureKind.DECODER,
                IjkRuntimeProfileStore.Metrics.EMPTY,
                NOW);

        IjkRuntimeProfileStore.Entry entry = store.recordFirstFrame(
                key, IjkRuntimeProfilePolicy.Path.IJK_HARD, NOW + 1).entry();

        assertEquals(1, entry.firstFrameCount());
        assertEquals(1, entry.consecutiveFailures());
        assertFalse(entry.verified());
    }

    @Test
    public void unhealthyMetricsRemainObservationOnly() {
        IjkRuntimeProfileStore store = store();
        IjkRuntimeProfileKey.Key key = key(1, 3_840);

        IjkRuntimeProfileStore.Entry entry = store.recordObservation(
                key,
                IjkRuntimeProfilePolicy.Path.IJK_SOFT,
                metrics(3, 250, 400, 1_024, 2_048),
                NOW).entry();

        assertNotNull(entry);
        assertFalse(entry.excluded());
        assertFalse(entry.verified());
        assertEquals(0, entry.failureCount());
        assertEquals(3, entry.rebufferCount());
        assertEquals(250, entry.maxDropRatePermille());
    }

    @Test
    public void emptyObservationIsSkipped() {
        IjkRuntimeProfileStore.RecordResult result = store().recordObservation(
                key(1, 3_840),
                IjkRuntimeProfilePolicy.Path.IJK_HARD,
                IjkRuntimeProfileStore.Metrics.EMPTY,
                NOW);

        assertFalse(result.recorded());
    }

    @Test
    public void fallbackResultsDoNotChangeExclusionState() {
        IjkRuntimeProfileStore store = store();
        IjkRuntimeProfileKey.Key key = key(1, 3_840);
        failTwice(store, key, IjkRuntimeProfilePolicy.Path.IJK_HARD);

        store.recordFallback(
                key, IjkRuntimeProfilePolicy.Path.IJK_HARD,
                IjkRuntimeProfileStore.FallbackResult.SUCCESS, NOW + 2);
        IjkRuntimeProfileStore.Entry entry = store.recordFallback(
                key, IjkRuntimeProfilePolicy.Path.IJK_HARD,
                IjkRuntimeProfileStore.FallbackResult.FAILURE, NOW + 3).entry();

        assertTrue(entry.excluded());
        assertEquals(1, entry.fallbackSuccessCount());
        assertEquals(1, entry.fallbackFailureCount());
    }

    @Test
    public void environmentFormatAndPathAreIndependent() {
        IjkRuntimeProfileStore store = store();
        IjkRuntimeProfileKey.Key base = key(1, 3_840);
        failTwice(store, base, IjkRuntimeProfilePolicy.Path.IJK_HARD);

        assertTrue(store.lookup(base,
                IjkRuntimeProfilePolicy.Path.IJK_HARD, NOW + 2).excluded());
        assertFalse(store.lookup(base,
                IjkRuntimeProfilePolicy.Path.MPV, NOW + 2).hit());
        assertFalse(store.lookup(key(2, 3_840),
                IjkRuntimeProfilePolicy.Path.IJK_HARD, NOW + 2).hit());
        assertFalse(store.lookup(key(1, 1_920),
                IjkRuntimeProfilePolicy.Path.IJK_HARD, NOW + 2).hit());
    }

    @Test
    public void entryExpiresAtExactTtlBoundary() {
        MemoryBackend backend = new MemoryBackend();
        IjkRuntimeProfileStore store = new IjkRuntimeProfileStore(backend);
        IjkRuntimeProfileKey.Key key = key(1, 3_840);
        store.recordFirstFrame(
                key, IjkRuntimeProfilePolicy.Path.IJK_HARD, NOW);

        assertTrue(store.lookup(
                key,
                IjkRuntimeProfilePolicy.Path.IJK_HARD,
                NOW + IjkRuntimeProfileStore.ENTRY_TTL_MS - 1).hit());
        assertFalse(store.lookup(
                key,
                IjkRuntimeProfilePolicy.Path.IJK_HARD,
                NOW + IjkRuntimeProfileStore.ENTRY_TTL_MS).hit());
        assertEquals("", backend.value);
    }

    @Test
    public void corruptOrIncompatibleStoreIsClearedSafely() {
        MemoryBackend backend = new MemoryBackend();
        backend.value = "store-version=99\n/Users/private/http://secret";
        IjkRuntimeProfileStore store = new IjkRuntimeProfileStore(backend);

        assertFalse(store.lookup(
                key(1, 3_840), IjkRuntimeProfilePolicy.Path.IJK_HARD, NOW).hit());
        assertEquals("", backend.value);
    }

    @Test
    public void persistedEntriesRoundTripWithThirtyTwoFields() {
        MemoryBackend backend = new MemoryBackend();
        IjkRuntimeProfileKey.Key key = key(1, 3_840);
        IjkRuntimeProfileStore first = new IjkRuntimeProfileStore(backend);
        failTwice(first, key, IjkRuntimeProfilePolicy.Path.IJK_HARD);

        String[] lines = backend.value.split("\\n");
        assertEquals(2, lines.length);
        assertEquals(32, lines[1].split("\\|", -1).length);

        IjkRuntimeProfileStore restored = new IjkRuntimeProfileStore(backend);
        IjkRuntimeProfileStore.Lookup lookup = restored.lookup(
                key, IjkRuntimeProfilePolicy.Path.IJK_HARD, NOW + 2);
        assertTrue(lookup.excluded());
        assertEquals(2, lookup.entry().failureCount());
    }

    @Test
    public void incompatibleEntryAndFutureTimestampAreDropped() {
        MemoryBackend versionBackend = new MemoryBackend();
        IjkRuntimeProfileKey.Key key = key(1, 3_840);
        new IjkRuntimeProfileStore(versionBackend).recordFirstFrame(
                key, IjkRuntimeProfilePolicy.Path.IJK_HARD, NOW);
        versionBackend.value = versionBackend.value.replaceFirst(
                "\\n1\\|", "\n99|");

        assertFalse(new IjkRuntimeProfileStore(versionBackend).lookup(
                key, IjkRuntimeProfilePolicy.Path.IJK_HARD, NOW).hit());
        assertEquals("", versionBackend.value);

        MemoryBackend futureBackend = new MemoryBackend();
        new IjkRuntimeProfileStore(futureBackend).recordFirstFrame(
                key,
                IjkRuntimeProfilePolicy.Path.IJK_HARD,
                NOW + IjkRuntimeProfileStore.MAX_FUTURE_SKEW_MS + 1);
        assertFalse(new IjkRuntimeProfileStore(futureBackend).lookup(
                key, IjkRuntimeProfilePolicy.Path.IJK_HARD, NOW).hit());
        assertEquals("", futureBackend.value);
    }

    @Test
    public void capacityPrefersExcludedThenVerifiedEntries() {
        IjkRuntimeProfileStore store = store();
        IjkRuntimeProfileKey.Key excluded = key(1, 1_001);
        IjkRuntimeProfileKey.Key verified = key(1, 1_002);
        failTwice(store, excluded, IjkRuntimeProfilePolicy.Path.IJK_HARD);
        store.recordStableSuccess(
                verified,
                IjkRuntimeProfilePolicy.Path.MPV,
                metrics(0, 0, 1_000, 0, 0),
                NOW + 2);
        for (int index = 0; index < IjkRuntimeProfileStore.MAX_ENTRIES; index++) {
            store.recordFirstFrame(
                    key(1, 2_000 + index),
                    IjkRuntimeProfilePolicy.Path.IJK_SOFT,
                    NOW + 10 + index);
        }

        assertEquals(IjkRuntimeProfileStore.MAX_ENTRIES,
                store.entryCount(NOW + 100));
        assertTrue(store.lookup(excluded,
                IjkRuntimeProfilePolicy.Path.IJK_HARD, NOW + 100).excluded());
        assertTrue(store.lookup(verified,
                IjkRuntimeProfilePolicy.Path.MPV, NOW + 100).verified());
        assertFalse(store.lookup(key(1, 2_000),
                IjkRuntimeProfilePolicy.Path.IJK_SOFT, NOW + 100).hit());
        assertFalse(store.lookup(key(1, 2_001),
                IjkRuntimeProfilePolicy.Path.IJK_SOFT, NOW + 100).hit());
    }

    @Test
    public void persistedPayloadContainsNoRawDeviceCodecUrlPathOrErrorText() {
        MemoryBackend backend = new MemoryBackend();
        new IjkRuntimeProfileStore(backend).recordFailure(
                key(1, 3_840),
                IjkRuntimeProfilePolicy.Path.IJK_HARD,
                IjkRuntimeProfilePolicy.FailureKind.DECODER,
                metrics(1, 2, 3, 4, 5),
                NOW);

        assertFalse(backend.value.contains("fingerprint"));
        assertFalse(backend.value.contains("hvc1.2.4.L153.B0"));
        assertFalse(backend.value.contains("http://"));
        assertFalse(backend.value.contains("/Users/"));
        assertFalse(backend.value.contains("exception message"));
    }

    private static IjkRuntimeProfileStore store() {
        return new IjkRuntimeProfileStore(new MemoryBackend());
    }

    private static void failTwice(
            IjkRuntimeProfileStore store,
            IjkRuntimeProfileKey.Key key,
            IjkRuntimeProfilePolicy.Path path) {
        store.recordFailure(
                key, path, IjkRuntimeProfilePolicy.FailureKind.DECODER,
                IjkRuntimeProfileStore.Metrics.EMPTY, NOW);
        store.recordFailure(
                key, path, IjkRuntimeProfilePolicy.FailureKind.RUNTIME,
                IjkRuntimeProfileStore.Metrics.EMPTY, NOW + 1);
    }

    private static IjkRuntimeProfileStore.Metrics metrics(
            int rebuffers,
            int drops,
            int rendered,
            long nativeGrowth,
            long pssGrowth) {
        return new IjkRuntimeProfileStore.Metrics(
                rebuffers, drops, rendered, nativeGrowth, pssGrowth);
    }

    private static IjkRuntimeProfileKey.Key key(
            int environmentValue,
            int width) {
        IjkRuntimeProfileKey.Environment environment =
                new IjkRuntimeProfileKey.Environment(digest(environmentValue));
        IjkRuntimeProfileKey.Key key = IjkRuntimeProfileKey.from(
                environment,
                new IjkRuntimeProfileKey.Evidence(
                        PlaybackAutoContext.Protocol.HLS,
                        PlaybackAutoContext.StreamKind.VOD,
                        MimeTypes.VIDEO_H265,
                        "hvc1.2.4.L153.B0",
                        2,
                        153,
                        width,
                        1_080,
                        30_000,
                        PlaybackAutoContext.HdrType.HDR10,
                        C.COLOR_SPACE_BT2020,
                        C.COLOR_RANGE_LIMITED,
                        C.COLOR_TRANSFER_ST2084,
                        true,
                        0,
                        PlaybackAutoContext.RenderTarget.SURFACE_VIEW));
        assertNotNull(key);
        return key;
    }

    private static String digest(int value) {
        return String.format(java.util.Locale.US, "%024x", value);
    }

    private static final class MemoryBackend
            implements IjkRuntimeProfileStore.Backend {

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
