package com.fongmi.android.tv.player;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaybackProfileAbStoreTest {

    private static final long NOW = 1_000_000L;

    @Test
    public void samplesRoundTripWithoutRawDeviceOrDecoderNames() {
        MemoryBackend backend = new MemoryBackend();
        PlaybackProfileAbStore store = new PlaybackProfileAbStore(backend);
        PlaybackProfileAbPolicy.GroupKey key = group("decoder-secret", 0);

        assertTrue(store.record(sample(
                key, PlaybackProfileAbPolicy.Arm.AUTO, NOW), NOW));
        assertTrue(store.record(sample(
                key, PlaybackProfileAbPolicy.Arm.LIGHTWEIGHT, NOW + 1),
                NOW + 1));
        PlaybackProfileAbStore restored =
                new PlaybackProfileAbStore(backend);
        PlaybackProfileAbStore.Snapshot snapshot =
                restored.snapshot(NOW + 1);

        assertEquals(1, snapshot.groups().size());
        assertEquals(1, snapshot.automaticSampleCount());
        assertEquals(0, snapshot.recommendedSampleCount());
        assertEquals(1, snapshot.lightweightSampleCount());
        assertFalse(backend.raw.contains("decoder-secret"));
        assertFalse(backend.raw.contains("fingerprint"));
        assertFalse(backend.raw.contains("http"));
    }

    @Test
    public void samplesExpireAtThirtyDaysAndFutureRecordsAreRejectedOnLoad() {
        MemoryBackend expiredBackend = new MemoryBackend();
        PlaybackProfileAbStore expired =
                new PlaybackProfileAbStore(expiredBackend);
        expired.record(sample(group("decoder", 0),
                PlaybackProfileAbPolicy.Arm.AUTO, NOW), NOW);

        assertEquals(0, new PlaybackProfileAbStore(expiredBackend)
                .snapshot(NOW + PlaybackProfileAbStore.SAMPLE_TTL_MS)
                .automaticSampleCount());
        assertEquals("", expiredBackend.raw);

        MemoryBackend futureBackend = new MemoryBackend();
        long future = NOW + PlaybackProfileAbStore.MAX_FUTURE_SKEW_MS + 1;
        PlaybackProfileAbStore futureStore =
                new PlaybackProfileAbStore(futureBackend);
        futureStore.record(sample(group("decoder", 1),
                PlaybackProfileAbPolicy.Arm.AUTO, future), future);

        assertEquals(0, new PlaybackProfileAbStore(futureBackend)
                .snapshot(NOW).automaticSampleCount());
    }

    @Test
    public void eachArmAndTotalGroupCountAreBounded() {
        MemoryBackend backend = new MemoryBackend();
        PlaybackProfileAbStore store = new PlaybackProfileAbStore(backend);
        PlaybackProfileAbPolicy.GroupKey first = group("decoder", 0);
        for (int index = 0; index < 40; index++) {
            long at = NOW + index;
            store.record(sample(first,
                    PlaybackProfileAbPolicy.Arm.AUTO, at), at);
        }
        assertEquals(PlaybackProfileAbStore.MAX_SAMPLES_PER_ARM,
                store.snapshot(NOW + 100).automaticSampleCount());

        for (int index = 1; index <= PlaybackProfileAbStore.MAX_GROUPS + 3;
             index++) {
            long at = NOW + 1_000 + index;
            store.record(sample(group("decoder", index),
                    PlaybackProfileAbPolicy.Arm.RECOMMENDED, at), at);
        }
        PlaybackProfileAbStore.Snapshot snapshot =
                store.snapshot(NOW + 10_000);
        assertEquals(PlaybackProfileAbStore.MAX_GROUPS,
                snapshot.groups().size());
    }

    @Test
    public void corruptStoreIsClearedInsteadOfPartiallyTrusted() {
        MemoryBackend backend = new MemoryBackend();
        backend.raw = "store-version=99\nraw|secret";

        PlaybackProfileAbStore.Snapshot snapshot =
                new PlaybackProfileAbStore(backend).snapshot(NOW);

        assertTrue(snapshot.groups().isEmpty());
        assertEquals("", backend.raw);
        assertTrue(backend.clears > 0);
    }

    @Test
    public void appOrMediaEnvironmentChangeDropsStaleDeviceGroups() {
        MemoryBackend backend = new MemoryBackend();
        PlaybackProfileAbStore store = new PlaybackProfileAbStore(backend);
        store.record(sample(group("decoder", 0),
                PlaybackProfileAbPolicy.Arm.AUTO, NOW), NOW);
        String upgradedDevice = PlaybackProfileAbIdentity.deviceDigest(
                "fingerprint", 11, "2", "media3-new");

        PlaybackProfileAbStore.Snapshot snapshot = store.snapshotForDevice(
                NOW, upgradedDevice);

        assertTrue(snapshot.groups().isEmpty());
        assertEquals("", backend.raw);
    }

    private static PlaybackProfileAbStore.Sample sample(
            PlaybackProfileAbPolicy.GroupKey key,
            PlaybackProfileAbPolicy.Arm arm,
            long at) {
        return new PlaybackProfileAbStore.Sample(
                PlaybackProfileAbStore.SAMPLE_VERSION,
                key,
                arm,
                800,
                1,
                500,
                60_000,
                180L * 1024 * 1024,
                2,
                -1,
                false,
                at);
    }

    private static PlaybackProfileAbPolicy.GroupKey group(
            String decoderPrefix,
            int suffix) {
        return new PlaybackProfileAbPolicy.GroupKey(
                PlaybackProfileAbIdentity.deviceDigest(
                        "fingerprint", 10, "1", "media3"),
                PlaybackAutoContext.Kernel.EXO,
                PlaybackAutoContext.DecodeMode.HARDWARE,
                PlaybackProfileAbIdentity.decoderDigest(
                        decoderPrefix + '-' + suffix),
                PlaybackAutoContext.Protocol.HLS,
                PlaybackAutoContext.StreamKind.VOD,
                PlaybackProfileAbPolicy.VideoMimeClass.HEVC,
                PlaybackAutoContext.HdrType.HDR10,
                PlaybackAutoContext.PathKind.REMOTE);
    }

    private static final class MemoryBackend
            implements PlaybackProfileAbStore.Backend {

        private String raw = "";
        private int clears;

        @Override
        public String read() {
            return raw;
        }

        @Override
        public void write(String value) {
            raw = value;
        }

        @Override
        public void clear() {
            raw = "";
            clears++;
        }
    }
}
