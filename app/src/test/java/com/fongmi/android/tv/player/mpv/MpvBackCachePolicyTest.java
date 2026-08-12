package com.fongmi.android.tv.player.mpv;

import com.fongmi.android.tv.player.PlaybackAutoContext;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MpvBackCachePolicyTest {

    private static final long MIB = 1024L * 1024L;

    @Test
    public void onlyExplicitSameItemBackwardSeekAtLeastFiveSecondsQualifies() {
        assertFalse(MpvBackCachePolicy.observeSeek(false, true, 20_000, 10_000).qualifying());
        assertFalse(MpvBackCachePolicy.observeSeek(true, false, 20_000, 10_000).qualifying());
        assertFalse(MpvBackCachePolicy.observeSeek(true, true, 10_000, 20_000).qualifying());
        assertFalse(MpvBackCachePolicy.observeSeek(true, true, 20_000, 16_000).qualifying());

        MpvBackCachePolicy.SeekObservation backward =
                MpvBackCachePolicy.observeSeek(true, true, 70_000, 10_000);
        assertTrue(backward.qualifying());
        assertTrue(backward.large());
        assertEquals(60_000, backward.distanceMs());
    }

    @Test
    public void learnedTiersRequireRepeatedBackwardSeekEvidence() {
        assertEquals(0, MpvBackCachePolicy.learnedTier(0));
        assertEquals(0, MpvBackCachePolicy.learnedTier(1));
        assertEquals(16 * MIB, MpvBackCachePolicy.learnedTier(2));
        assertEquals(32 * MIB, MpvBackCachePolicy.learnedTier(3));
        assertEquals(64 * MIB, MpvBackCachePolicy.learnedTier(4));
    }

    @Test
    public void backUsesOnlyUnusedTotalBudgetAndNeverExceedsForwardTarget() {
        assertEquals(32 * MIB, resolve(request(64, 96)).safeBackBytes());
        assertEquals(64 * MIB, resolve(request(64, 128)).safeBackBytes());
        assertEquals(16 * MIB, resolve(request(24, 192)).safeBackBytes());
        assertEquals(0, resolve(request(96, 96)).safeBackBytes());
    }

    @Test
    public void capacityRoundsDownToSupportedBackTiers() {
        assertEquals(0, MpvBackCachePolicy.tierForCapacity(15 * MIB));
        assertEquals(16 * MIB, MpvBackCachePolicy.tierForCapacity(31 * MIB));
        assertEquals(32 * MIB, MpvBackCachePolicy.tierForCapacity(63 * MIB));
        assertEquals(64 * MIB, MpvBackCachePolicy.tierForCapacity(128 * MIB));
    }

    @Test
    public void moderateCriticalAndUnknownMemoryForceZero() {
        MpvBackCachePolicy.Request moderate = withMemory(
                request(64, 128), true,
                PlaybackAutoContext.MemoryPressure.MODERATE, true, false);
        MpvBackCachePolicy.Request critical = withMemory(
                request(64, 128), true,
                PlaybackAutoContext.MemoryPressure.CRITICAL, true, true);
        MpvBackCachePolicy.Request unknown = withMemory(
                request(64, 128), false,
                PlaybackAutoContext.MemoryPressure.UNKNOWN, false, false);

        assertEquals(MpvBackCachePolicy.Reason.MODERATE_PRESSURE, resolve(moderate).reason());
        assertEquals(MpvBackCachePolicy.Reason.CRITICAL_PRESSURE, resolve(critical).reason());
        assertEquals(MpvBackCachePolicy.Reason.MEMORY_UNKNOWN, resolve(unknown).reason());
        assertEquals(0, resolve(moderate).safeBackBytes());
        assertEquals(0, resolve(critical).safeBackBytes());
        assertEquals(0, resolve(unknown).safeBackBytes());
    }

    @Test
    public void liveUnseekableAndOpaquePathsAreIneligible() {
        MpvBackCachePolicy.Request live = copy(
                request(64, 128), true, true,
                true, PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                true, PlaybackAutoContext.StreamKind.VOD,
                true, PlaybackAutoContext.PathKind.REMOTE,
                true, PlaybackAutoContext.UpstreamState.VISIBLE);
        MpvBackCachePolicy.Request unseekable = copy(
                request(64, 128), false, false,
                true, PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                true, PlaybackAutoContext.StreamKind.VOD,
                true, PlaybackAutoContext.PathKind.REMOTE,
                true, PlaybackAutoContext.UpstreamState.VISIBLE);
        MpvBackCachePolicy.Request opaque = copy(
                request(64, 128), true, false,
                true, PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                true, PlaybackAutoContext.StreamKind.VOD,
                true, PlaybackAutoContext.PathKind.EXTERNAL_LOOPBACK,
                true, PlaybackAutoContext.UpstreamState.OPAQUE);

        assertEquals(MpvBackCachePolicy.Reason.LIVE_RESOURCE, resolve(live).reason());
        assertEquals(MpvBackCachePolicy.Reason.NOT_SEEKABLE, resolve(unseekable).reason());
        assertEquals(MpvBackCachePolicy.Reason.OPAQUE_PATH, resolve(opaque).reason());
        assertFalse(resolve(live).eligible());
        assertFalse(resolve(unseekable).eligible());
        assertFalse(resolve(opaque).eligible());
    }

    @Test
    public void automaticAndPerformancePriorityRemainHardBoundaries() {
        MpvBackCachePolicy.Request base = request(64, 128);
        MpvBackCachePolicy.Request inactive = new MpvBackCachePolicy.Request(
                false, true, true,
                base.seekable(), base.currentMediaLive(),
                base.protocolUsable(), base.protocol(),
                base.streamKindUsable(), base.streamKind(),
                base.playerPathUsable(), base.playerPath(),
                base.upstreamStateUsable(), base.upstreamState(),
                base.pressureUsable(), base.memoryPressure(), base.snapshotUsable(),
                base.memorySampleAtElapsedMs(), base.critical(),
                base.forwardBytes(), base.totalBudgetBytes());
        MpvBackCachePolicy.Request config = new MpvBackCachePolicy.Request(
                true, true, false,
                base.seekable(), base.currentMediaLive(),
                base.protocolUsable(), base.protocol(),
                base.streamKindUsable(), base.streamKind(),
                base.playerPathUsable(), base.playerPath(),
                base.upstreamStateUsable(), base.upstreamState(),
                base.pressureUsable(), base.memoryPressure(), base.snapshotUsable(),
                base.memorySampleAtElapsedMs(), base.critical(),
                base.forwardBytes(), base.totalBudgetBytes());

        assertFalse(resolve(inactive).active());
        assertEquals(MpvBackCachePolicy.Reason.CONFIG_PRIORITY, resolve(config).reason());
    }

    private static MpvBackCachePolicy.Assessment resolve(MpvBackCachePolicy.Request request) {
        return MpvBackCachePolicy.resolve(request);
    }

    private static MpvBackCachePolicy.Request request(long forwardMib, long totalMib) {
        return new MpvBackCachePolicy.Request(
                true,
                true,
                true,
                true,
                false,
                true,
                PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                true,
                PlaybackAutoContext.StreamKind.VOD,
                true,
                PlaybackAutoContext.PathKind.REMOTE,
                true,
                PlaybackAutoContext.UpstreamState.VISIBLE,
                true,
                PlaybackAutoContext.MemoryPressure.NORMAL,
                true,
                10,
                false,
                forwardMib * MIB,
                totalMib * MIB);
    }

    private static MpvBackCachePolicy.Request withMemory(
            MpvBackCachePolicy.Request base,
            boolean pressureUsable,
            PlaybackAutoContext.MemoryPressure pressure,
            boolean snapshotUsable,
            boolean critical) {
        return new MpvBackCachePolicy.Request(
                base.automatic(), base.mpv(), base.performancePriority(),
                base.seekable(), base.currentMediaLive(),
                base.protocolUsable(), base.protocol(),
                base.streamKindUsable(), base.streamKind(),
                base.playerPathUsable(), base.playerPath(),
                base.upstreamStateUsable(), base.upstreamState(),
                pressureUsable, pressure, snapshotUsable, 20, critical,
                base.forwardBytes(), base.totalBudgetBytes());
    }

    private static MpvBackCachePolicy.Request copy(
            MpvBackCachePolicy.Request base,
            boolean seekable,
            boolean live,
            boolean protocolUsable,
            PlaybackAutoContext.Protocol protocol,
            boolean streamUsable,
            PlaybackAutoContext.StreamKind stream,
            boolean playerPathUsable,
            PlaybackAutoContext.PathKind playerPath,
            boolean upstreamUsable,
            PlaybackAutoContext.UpstreamState upstreamState) {
        return new MpvBackCachePolicy.Request(
                base.automatic(), base.mpv(), base.performancePriority(),
                seekable, live,
                protocolUsable, protocol,
                streamUsable, stream,
                playerPathUsable, playerPath,
                upstreamUsable, upstreamState,
                base.pressureUsable(), base.memoryPressure(), base.snapshotUsable(),
                base.memorySampleAtElapsedMs(), base.critical(),
                base.forwardBytes(), base.totalBudgetBytes());
    }
}
