package com.fongmi.android.tv.player.ijk;

import com.fongmi.android.tv.player.PlaybackAutoContext;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IjkBufferPolicyTest {

    private static final long MIB = 1024L * 1024L;

    @Test
    public void nonAutomaticProfileIsNotManaged() {
        IjkBufferPolicy.Decision decision = IjkBufferPolicy.resolve(
                request(false, PlaybackAutoContext.StreamKind.VOD,
                        PlaybackAutoContext.ManifestFacts.none(), normalMemory(),
                        0, 0, -1));

        assertFalse(decision.managed());
        assertEquals(IjkBufferPolicy.Reason.NOT_AUTOMATIC_IJK,
                decision.reason());
    }

    @Test
    public void unknownSceneAndMemoryUseFiniteBalancedInitialConfig() {
        IjkBufferPolicy.Decision decision = IjkBufferPolicy.resolve(
                new IjkBufferPolicy.Request(true, true, false,
                        PlaybackAutoContext.Protocol.UNKNOWN, false,
                        PlaybackAutoContext.StreamKind.UNKNOWN, false,
                        PlaybackAutoContext.ManifestFacts.unknown(), false,
                        PlaybackAutoContext.MemoryPressure.UNKNOWN, false,
                        PlaybackAutoContext.MemorySnapshot.unknown(), false, 0,
                        false, 0, false, -1));

        assertTrue(decision.managed());
        assertEquals(8, decision.target().bufferMb());
        assertEquals(100, decision.target().firstWaterMs());
        assertEquals(1_000, decision.target().nextWaterMs());
        assertEquals(3_000, decision.target().lastWaterMs());
        assertEquals(IjkBufferPolicy.Reason.MEMORY_UNKNOWN, decision.reason());
    }

    @Test
    public void criticalAndLowRamMemoryForceFourMib() {
        IjkBufferPolicy.Decision critical = IjkBufferPolicy.resolve(
                request(true, PlaybackAutoContext.StreamKind.VOD,
                        PlaybackAutoContext.ManifestFacts.none(),
                        memory(PlaybackAutoContext.MemoryPressure.CRITICAL,
                                false, 512 * MIB, 256 * MIB,
                                2_000 * MIB, 256 * MIB), 80_000_000, 2, -1));
        IjkBufferPolicy.Decision lowRam = IjkBufferPolicy.resolve(
                request(true, PlaybackAutoContext.StreamKind.VOD,
                        PlaybackAutoContext.ManifestFacts.none(),
                        memory(PlaybackAutoContext.MemoryPressure.NORMAL,
                                true, 512 * MIB, 256 * MIB,
                                2_000 * MIB, 256 * MIB), 80_000_000, 2, -1));

        assertEquals(4, critical.target().bufferMb());
        assertEquals(IjkBufferPolicy.Reason.CRITICAL_MEMORY, critical.reason());
        assertEquals(4, lowRam.target().bufferMb());
        assertEquals(IjkBufferPolicy.Reason.LOW_RAM_DEVICE, lowRam.reason());
    }

    @Test
    public void normalVodStartsBalancedAndCanExpandFromDemandOrRebuffer() {
        IjkBufferPolicy.Decision baseline = IjkBufferPolicy.resolve(
                request(true, PlaybackAutoContext.StreamKind.VOD,
                        PlaybackAutoContext.ManifestFacts.none(), normalMemory(),
                        0, 0, -1));
        IjkBufferPolicy.Decision highBitrate = IjkBufferPolicy.resolve(
                request(true, PlaybackAutoContext.StreamKind.VOD,
                        PlaybackAutoContext.ManifestFacts.none(), normalMemory(),
                        30_000_000, 0, -1));
        IjkBufferPolicy.Decision rebuffer = IjkBufferPolicy.resolve(
                request(true, PlaybackAutoContext.StreamKind.VOD,
                        PlaybackAutoContext.ManifestFacts.none(), normalMemory(),
                        0, 1, -1));

        assertEquals(8, baseline.target().bufferMb());
        assertEquals(15, baseline.memoryCeilingMb());
        assertEquals(15, highBitrate.target().bufferMb());
        assertEquals(IjkBufferPolicy.Reason.MEDIA_DEMAND, highBitrate.reason());
        assertEquals(15, rebuffer.target().bufferMb());
        assertEquals(IjkBufferPolicy.Reason.REBUFFER_HEADROOM, rebuffer.reason());
    }

    @Test
    public void moderateMemoryCapsDemandAndRebufferAtEightMib() {
        IjkBufferPolicy.Decision decision = IjkBufferPolicy.resolve(
                request(true, PlaybackAutoContext.StreamKind.VOD,
                        PlaybackAutoContext.ManifestFacts.none(),
                        memory(PlaybackAutoContext.MemoryPressure.MODERATE,
                                false, 512 * MIB, 256 * MIB,
                                2_000 * MIB, 256 * MIB), 80_000_000, 3, -1));

        assertEquals(8, decision.target().bufferMb());
        assertEquals(8, decision.memoryCeilingMb());
        assertEquals(IjkBufferPolicy.Reason.MODERATE_MEMORY, decision.reason());
    }

    @Test
    public void incompleteNormalMemoryFactsCannotExpandBeyondEightMib() {
        PlaybackAutoContext.MemorySnapshot partial =
                new PlaybackAutoContext.MemorySnapshot(
                        PlaybackAutoContext.MemoryTrigger.PERIODIC,
                        null, null, null, false,
                        null, null, null, false,
                        null, null, 64 * MIB);
        IjkBufferPolicy.Decision decision = IjkBufferPolicy.resolve(
                request(true, PlaybackAutoContext.StreamKind.VOD,
                        PlaybackAutoContext.ManifestFacts.none(),
                        new Memory(PlaybackAutoContext.MemoryPressure.NORMAL,
                                partial),
                        80_000_000, 2, -1));

        assertEquals(8, decision.target().bufferMb());
        assertEquals(8, decision.memoryCeilingMb());
        assertEquals(IjkBufferPolicy.Reason.MEMORY_UNKNOWN,
                decision.reason());
    }

    @Test
    public void lowLatencyUsesPartCadenceAndFourMibBaseline() {
        PlaybackAutoContext.ManifestFacts manifest = new PlaybackAutoContext.ManifestFacts(
                PlaybackAutoContext.ManifestKind.HLS_MEDIA, false,
                2_000L, 333L, 1_200L, null, false, true);

        IjkBufferPolicy.Decision decision = IjkBufferPolicy.resolve(
                request(true, PlaybackAutoContext.StreamKind.LOW_LATENCY_LIVE,
                        manifest, normalMemory(), 0, 0, 1_200));

        assertEquals(4, decision.target().bufferMb());
        assertEquals(166, decision.target().firstWaterMs());
        assertEquals(666, decision.target().nextWaterMs());
        assertEquals(1_200, decision.target().lastWaterMs());
        assertEquals(1_200, decision.targetOffsetMs());
    }

    @Test
    public void regularLiveUsesTargetCadenceAndRebufferRaisesHeadroom() {
        PlaybackAutoContext.ManifestFacts manifest = new PlaybackAutoContext.ManifestFacts(
                PlaybackAutoContext.ManifestKind.HLS_MEDIA, false,
                6_000L, null, 12_000L, null, false, false);

        IjkBufferPolicy.Decision baseline = IjkBufferPolicy.resolve(
                request(true, PlaybackAutoContext.StreamKind.LIVE,
                        manifest, normalMemory(), 0, 0, 12_000));
        IjkBufferPolicy.Decision rebuffer = IjkBufferPolicy.resolve(
                request(true, PlaybackAutoContext.StreamKind.LIVE,
                        manifest, normalMemory(), 0, 1, 12_000));

        assertEquals(8, baseline.target().bufferMb());
        assertEquals(500, baseline.target().firstWaterMs());
        assertEquals(1_500, baseline.target().nextWaterMs());
        assertEquals(3_000, baseline.target().lastWaterMs());
        assertEquals(15, rebuffer.target().bufferMb());
        assertEquals(2_000, rebuffer.target().nextWaterMs());
        assertEquals(5_000, rebuffer.target().lastWaterMs());
    }

    @Test
    public void excessiveLiveLagForcesLowQueueAndLowWater() {
        PlaybackAutoContext.ManifestFacts manifest = new PlaybackAutoContext.ManifestFacts(
                PlaybackAutoContext.ManifestKind.HLS_MEDIA, false,
                6_000L, null, 12_000L, null, false, false);

        IjkBufferPolicy.Decision decision = IjkBufferPolicy.resolve(
                request(true, PlaybackAutoContext.StreamKind.LIVE,
                        manifest, normalMemory(), 80_000_000, 2, 20_001));

        assertTrue(decision.liveLagHigh());
        assertEquals(4, decision.target().bufferMb());
        assertEquals(100, decision.target().firstWaterMs());
        assertEquals(300, decision.target().nextWaterMs());
        assertEquals(1_000, decision.target().lastWaterMs());
        assertEquals(IjkBufferPolicy.Reason.LIVE_LAG_HIGH, decision.reason());
    }

    @Test
    public void hugeBitrateDoesNotOverflowAndCapsAtFifteenMib() {
        IjkBufferPolicy.Decision decision = IjkBufferPolicy.resolve(
                request(true, PlaybackAutoContext.StreamKind.VOD,
                        PlaybackAutoContext.ManifestFacts.none(), normalMemory(),
                        Long.MAX_VALUE, 0, -1));

        assertEquals(15, decision.target().bufferMb());
        assertTrue(decision.mediaDemandBytes() > 15L * MIB);
    }

    @Test
    public void configNormalizesSupportedTiersAndOrderedWatermarks() {
        IjkBufferPolicy.Config config = new IjkBufferPolicy.Config(5,
                50, 10, 10_000);

        assertEquals(8, config.bufferMb());
        assertEquals(100, config.firstWaterMs());
        assertEquals(100, config.nextWaterMs());
        assertEquals(5_000, config.lastWaterMs());
        assertEquals(8L * MIB, config.maxBufferBytes());
    }

    private static IjkBufferPolicy.Request request(
            boolean automatic,
            PlaybackAutoContext.StreamKind stream,
            PlaybackAutoContext.ManifestFacts manifest,
            Memory memory,
            long bitrate,
            int rebufferCount,
            long liveLagMs) {
        return new IjkBufferPolicy.Request(automatic, true, true,
                PlaybackAutoContext.Protocol.HLS, true, stream, true, manifest,
                true, memory.pressure(), true, memory.snapshot(), bitrate > 0,
                bitrate, true, rebufferCount, liveLagMs >= 0, liveLagMs);
    }

    private static Memory normalMemory() {
        return memory(PlaybackAutoContext.MemoryPressure.NORMAL, false,
                512 * MIB, 256 * MIB, 2_000 * MIB, 256 * MIB);
    }

    private static Memory memory(
            PlaybackAutoContext.MemoryPressure pressure,
            boolean lowRam,
            long heapLimit,
            long heapHeadroom,
            long systemAvailable,
            long systemThreshold) {
        PlaybackAutoContext.MemorySnapshot snapshot = new PlaybackAutoContext.MemorySnapshot(
                PlaybackAutoContext.MemoryTrigger.PERIODIC,
                Math.max(0, heapLimit - heapHeadroom), heapLimit, heapHeadroom,
                lowRam, 4_000 * MIB, systemAvailable, systemThreshold, false,
                null, null, 64 * MIB);
        return new Memory(pressure, snapshot);
    }

    private record Memory(
            PlaybackAutoContext.MemoryPressure pressure,
            PlaybackAutoContext.MemorySnapshot snapshot) {
    }
}
