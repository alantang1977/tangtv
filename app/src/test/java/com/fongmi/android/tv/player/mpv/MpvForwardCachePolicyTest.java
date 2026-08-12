package com.fongmi.android.tv.player.mpv;

import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.PlaybackRoute;
import com.fongmi.android.tv.player.PlaybackRouteCapabilities;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MpvForwardCachePolicyTest {

    private static final long MIB = 1024L * 1024L;

    @Test
    public void inactiveAndConfigPriorityNeverProduceActiveAssessment() {
        PlaybackAutoContext context = context(
                memory(512, 384, 1536, false, false),
                PlaybackAutoContext.MemoryPressure.NORMAL,
                20_000_000L, 0, 0,
                PlaybackAutoContext.Confidence.HIGH,
                PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                PlaybackAutoContext.StreamKind.VOD,
                PlaybackAutoContext.PathKind.REMOTE,
                PlaybackAutoContext.UpstreamState.VISIBLE);

        assertFalse(MpvForwardCachePolicy.assess(
                context, false, true, true, 64 * MIB, 1).active());
        MpvForwardCachePolicy.Assessment config = MpvForwardCachePolicy.assess(
                context, true, true, false, 64 * MIB, 1);
        assertFalse(config.active());
        assertEquals(MpvForwardCachePolicy.Reason.CONFIG_PRIORITY, config.inactiveReason());
    }

    @Test
    public void trackAverageAndPeakUseThirtyAndTenSecondDemandWindows() {
        PlaybackAutoContext context = context(
                memory(512, 384, 1536, false, false),
                PlaybackAutoContext.MemoryPressure.NORMAL,
                20_000_000L, 1_000_000L, 40_000_000L,
                PlaybackAutoContext.Confidence.HIGH,
                PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                PlaybackAutoContext.StreamKind.VOD,
                PlaybackAutoContext.PathKind.REMOTE,
                PlaybackAutoContext.UpstreamState.VISIBLE);

        MpvForwardCachePolicy.Assessment assessment = assess(context, 64);

        assertEquals(21_000_000L, assessment.averageBitrate().bitsPerSecond());
        assertEquals(41_000_000L, assessment.peakBitrate().bitsPerSecond());
        assertEquals(90_562_500L, assessment.averageDemandBytes());
        assertEquals(51_250_000L, assessment.burstDemandBytes());
        assertEquals(96 * MIB, assessment.mediaTargetBytes());
    }

    @Test
    public void selectedTrackFactsTakePriorityOverRuntimeMediaFallback() {
        PlaybackAutoContext base = context(
                memory(512, 384, 1536, false, false),
                PlaybackAutoContext.MemoryPressure.NORMAL,
                10_000_000L, 0, 0,
                PlaybackAutoContext.Confidence.HIGH,
                PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                PlaybackAutoContext.StreamKind.VOD,
                PlaybackAutoContext.PathKind.REMOTE,
                PlaybackAutoContext.UpstreamState.VISIBLE);
        PlaybackAutoContext context = withRuntime(base, 100_000_000L,
                PlaybackAutoContext.Confidence.MEDIUM, 0, false);

        MpvForwardCachePolicy.Assessment assessment = assess(context, 64);

        assertEquals(MpvForwardCachePolicy.Source.TRACK_FORMAT,
                assessment.averageBitrate().source());
        assertEquals(48 * MIB, assessment.mediaTargetBytes());
    }

    @Test
    public void runtimeMediaBitrateIsFallbackButDownloadBandwidthIsNotDemand() {
        PlaybackAutoContext base = context(
                memory(512, 384, 1536, false, false),
                PlaybackAutoContext.MemoryPressure.NORMAL,
                0, 0, 0,
                PlaybackAutoContext.Confidence.UNKNOWN,
                PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                PlaybackAutoContext.StreamKind.VOD,
                PlaybackAutoContext.PathKind.REMOTE,
                PlaybackAutoContext.UpstreamState.VISIBLE);
        PlaybackAutoContext runtime = withRuntime(base, 20_000_000L,
                PlaybackAutoContext.Confidence.MEDIUM, 900_000_000L, false);

        MpvForwardCachePolicy.Assessment assessment = assess(runtime, 64);

        assertTrue(assessment.mediaReliable());
        assertEquals(MpvForwardCachePolicy.Source.RUNTIME_MEDIA,
                assessment.averageBitrate().source());
        assertEquals(96 * MIB, assessment.mediaTargetBytes());
    }

    @Test
    public void lowConfidenceOrExpiredRuntimeCannotDriveExpansion() {
        PlaybackAutoContext base = context(
                memory(512, 384, 1536, false, false),
                PlaybackAutoContext.MemoryPressure.NORMAL,
                0, 0, 0,
                PlaybackAutoContext.Confidence.UNKNOWN,
                PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                PlaybackAutoContext.StreamKind.VOD,
                PlaybackAutoContext.PathKind.REMOTE,
                PlaybackAutoContext.UpstreamState.VISIBLE);
        PlaybackAutoContext low = withRuntime(base, 100_000_000L,
                PlaybackAutoContext.Confidence.LOW, 0, false);
        PlaybackAutoContext expired = withRuntime(base, 100_000_000L,
                PlaybackAutoContext.Confidence.HIGH, 0, true);

        assertFalse(assess(low, 64).mediaReliable());
        assertFalse(MpvForwardCachePolicy.assess(
                expired, true, true, true, 64 * MIB, 10).mediaReliable());
    }

    @Test
    public void unknownMemoryCannotRaiseSafeCapacityAboveInitialBaseline() {
        PlaybackAutoContext context = context(
                null,
                PlaybackAutoContext.MemoryPressure.UNKNOWN,
                100_000_000L, 0, 0,
                PlaybackAutoContext.Confidence.HIGH,
                PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                PlaybackAutoContext.StreamKind.VOD,
                PlaybackAutoContext.PathKind.REMOTE,
                PlaybackAutoContext.UpstreamState.VISIBLE);

        MpvForwardCachePolicy.Assessment assessment = assess(context, 64);

        assertEquals(64 * MIB, assessment.safeTargetBytes());
        assertEquals(MpvForwardCachePolicy.Reason.MEMORY_UNKNOWN,
                assessment.limitingReason());
    }

    @Test
    public void capacityThresholdsMapToConservativeProductTiers() {
        assertEquals(64 * MIB, assessWithMemory(256, 128, 512).capacityTargetBytes());
        assertEquals(96 * MIB, assessWithMemory(384, 192, 768).capacityTargetBytes());
        assertEquals(128 * MIB, assessWithMemory(512, 256, 1024).capacityTargetBytes());
        assertEquals(192 * MIB, assessWithMemory(512, 384, 1536).capacityTargetBytes());
    }

    @Test
    public void lowRamSmallHeapAndLowHeadroomStayAtLowTiers() {
        PlaybackAutoContext lowRam = context(
                memory(512, 384, 1536, true, false),
                PlaybackAutoContext.MemoryPressure.NORMAL,
                100_000_000L, 0, 0,
                PlaybackAutoContext.Confidence.HIGH,
                PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                PlaybackAutoContext.StreamKind.VOD,
                PlaybackAutoContext.PathKind.REMOTE,
                PlaybackAutoContext.UpstreamState.VISIBLE);
        PlaybackAutoContext smallHeap = context(
                memory(192, 96, 1536, false, false),
                PlaybackAutoContext.MemoryPressure.NORMAL,
                100_000_000L, 0, 0,
                PlaybackAutoContext.Confidence.HIGH,
                PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                PlaybackAutoContext.StreamKind.VOD,
                PlaybackAutoContext.PathKind.REMOTE,
                PlaybackAutoContext.UpstreamState.VISIBLE);
        PlaybackAutoContext lowHeadroom = context(
                memory(512, 64, 1536, false, false),
                PlaybackAutoContext.MemoryPressure.NORMAL,
                100_000_000L, 0, 0,
                PlaybackAutoContext.Confidence.HIGH,
                PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                PlaybackAutoContext.StreamKind.VOD,
                PlaybackAutoContext.PathKind.REMOTE,
                PlaybackAutoContext.UpstreamState.VISIBLE);

        assertEquals(48 * MIB, assess(lowRam, 64).capacityTargetBytes());
        assertEquals(48 * MIB, assess(smallHeap, 64).capacityTargetBytes());
        assertEquals(24 * MIB, assess(lowHeadroom, 64).capacityTargetBytes());
    }

    @Test
    public void criticalPressureOrSystemThresholdImmediatelyCapsAtMinimum() {
        PlaybackAutoContext pressure = context(
                memory(512, 384, 1536, false, false),
                PlaybackAutoContext.MemoryPressure.CRITICAL,
                100_000_000L, 0, 0,
                PlaybackAutoContext.Confidence.HIGH,
                PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                PlaybackAutoContext.StreamKind.VOD,
                PlaybackAutoContext.PathKind.REMOTE,
                PlaybackAutoContext.UpstreamState.VISIBLE);
        PlaybackAutoContext systemLow = context(
                memory(512, 384, 0, false, true),
                PlaybackAutoContext.MemoryPressure.NORMAL,
                100_000_000L, 0, 0,
                PlaybackAutoContext.Confidence.HIGH,
                PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                PlaybackAutoContext.StreamKind.VOD,
                PlaybackAutoContext.PathKind.REMOTE,
                PlaybackAutoContext.UpstreamState.VISIBLE);

        assertTrue(assess(pressure, 64).critical());
        assertEquals(24 * MIB, assess(pressure, 64).safeTargetBytes());
        assertTrue(assess(systemLow, 64).critical());
        assertEquals(24 * MIB, assess(systemLow, 64).safeTargetBytes());
    }

    @Test
    public void localLiveAndOpaquePathsTightenOtherwiseLargeCapacity() {
        PlaybackAutoContext.MemorySnapshot memory = memory(512, 384, 1536, false, false);
        PlaybackAutoContext local = context(memory, PlaybackAutoContext.MemoryPressure.NORMAL,
                100_000_000L, 0, 0, PlaybackAutoContext.Confidence.HIGH,
                PlaybackAutoContext.Protocol.LOCAL, PlaybackAutoContext.StreamKind.VOD,
                PlaybackAutoContext.PathKind.LOCAL,
                PlaybackAutoContext.UpstreamState.NOT_APPLICABLE);
        PlaybackAutoContext live = context(memory, PlaybackAutoContext.MemoryPressure.NORMAL,
                100_000_000L, 0, 0, PlaybackAutoContext.Confidence.HIGH,
                PlaybackAutoContext.Protocol.HLS, PlaybackAutoContext.StreamKind.LIVE,
                PlaybackAutoContext.PathKind.REMOTE,
                PlaybackAutoContext.UpstreamState.VISIBLE);
        PlaybackAutoContext opaque = context(memory, PlaybackAutoContext.MemoryPressure.NORMAL,
                100_000_000L, 0, 0, PlaybackAutoContext.Confidence.HIGH,
                PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP, PlaybackAutoContext.StreamKind.VOD,
                PlaybackAutoContext.PathKind.EXTERNAL_LOOPBACK,
                PlaybackAutoContext.UpstreamState.OPAQUE);

        assertEquals(24 * MIB, assess(local, 64).safeTargetBytes());
        assertEquals(48 * MIB, assess(live, 64).safeTargetBytes());
        assertEquals(48 * MIB, assess(opaque, 64).safeTargetBytes());
    }

    private static MpvForwardCachePolicy.Assessment assess(
            PlaybackAutoContext context,
            int baselineMib) {
        return MpvForwardCachePolicy.assess(
                context, true, true, true, baselineMib * MIB, 1);
    }

    private static MpvForwardCachePolicy.Assessment assessWithMemory(
            long heapLimitMib,
            long headroomMib,
            long surplusMib) {
        PlaybackAutoContext context = context(
                memory(heapLimitMib, headroomMib, surplusMib, false, false),
                PlaybackAutoContext.MemoryPressure.NORMAL,
                100_000_000L, 0, 0,
                PlaybackAutoContext.Confidence.HIGH,
                PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                PlaybackAutoContext.StreamKind.VOD,
                PlaybackAutoContext.PathKind.REMOTE,
                PlaybackAutoContext.UpstreamState.VISIBLE);
        return assess(context, 64);
    }

    private static PlaybackAutoContext context(
            PlaybackAutoContext.MemorySnapshot memory,
            PlaybackAutoContext.MemoryPressure pressure,
            long videoAverage,
            long audioAverage,
            long videoPeak,
            PlaybackAutoContext.Confidence mediaConfidence,
            PlaybackAutoContext.Protocol protocol,
            PlaybackAutoContext.StreamKind stream,
            PlaybackAutoContext.PathKind playerPath,
            PlaybackAutoContext.UpstreamState upstreamState) {
        PlaybackAutoContext.Fact<PlaybackAutoContext.MemoryPressure> pressureFact =
                pressure == PlaybackAutoContext.MemoryPressure.UNKNOWN
                        ? PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.MemoryPressure.UNKNOWN)
                        : fact(pressure, PlaybackAutoContext.ValueSource.SYSTEM_API,
                        PlaybackAutoContext.Confidence.HIGH);
        PlaybackAutoContext.Fact<PlaybackAutoContext.MemorySnapshot> memoryFact = memory == null
                ? PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.MemorySnapshot.unknown())
                : fact(memory, PlaybackAutoContext.ValueSource.SYSTEM_API,
                PlaybackAutoContext.Confidence.HIGH);
        PlaybackAutoContext.DeviceFacts device = new PlaybackAutoContext.DeviceFacts(
                pressureFact,
                memoryFact,
                PlaybackAutoContext.Fact.unknown(-1L),
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.ThermalState.UNKNOWN),
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.PowerState.UNKNOWN),
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.NetworkCost.UNKNOWN),
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.NetworkSnapshot.unknown()));
        PlaybackAutoContext.ResourceFacts resource = new PlaybackAutoContext.ResourceFacts(
                fact(protocol, PlaybackAutoContext.ValueSource.PLAYBACK_REQUEST,
                        PlaybackAutoContext.Confidence.HIGH),
                fact(stream, PlaybackAutoContext.ValueSource.PLAYBACK_REQUEST,
                        PlaybackAutoContext.Confidence.HIGH),
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.RangeSupport.UNKNOWN),
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.TransferUnit.UNKNOWN));
        PlaybackAutoContext.PathFacts path = new PlaybackAutoContext.PathFacts(
                PlaybackAutoContext.Fact.unknown(PlaybackRoute.OTHER),
                PlaybackAutoContext.Fact.unknown(PlaybackRoute.Owner.UNKNOWN),
                PlaybackAutoContext.Fact.unknown(false),
                PlaybackAutoContext.Fact.unknown(PlaybackRouteCapabilities.ObservedLeg.SOURCE_SPECIFIC),
                PlaybackAutoContext.Fact.unknown(PlaybackRouteCapabilities.UpstreamVisibility.UNKNOWN),
                PlaybackAutoContext.Fact.unknown(PlaybackRouteCapabilities.ControlScope.NONE),
                fact(playerPath, PlaybackAutoContext.ValueSource.ROUTE_CLASSIFIER,
                        PlaybackAutoContext.Confidence.HIGH),
                playerPath == PlaybackAutoContext.PathKind.LOCAL
                        ? fact(PlaybackAutoContext.PathKind.LOCAL,
                        PlaybackAutoContext.ValueSource.ROUTE_CLASSIFIER,
                        PlaybackAutoContext.Confidence.HIGH)
                        : PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.PathKind.UNKNOWN),
                fact(upstreamState, PlaybackAutoContext.ValueSource.ROUTE_CLASSIFIER,
                        PlaybackAutoContext.Confidence.HIGH));
        PlaybackAutoContext.TrackFacts video = track(videoAverage, videoPeak, mediaConfidence);
        PlaybackAutoContext.TrackFacts audio = track(audioAverage, 0, mediaConfidence);
        PlaybackAutoContext.MediaFacts media = new PlaybackAutoContext.MediaFacts(
                1, video, audio, PlaybackAutoContext.DecoderFacts.unknown(1),
                PlaybackAutoContext.OutputFacts.unknown(), PlaybackAutoContext.DisplayFacts.unknown());
        return new PlaybackAutoContext(
                new PlaybackAutoContext.SessionToken("p-mfc-policy-1", 1),
                0, 0, 0,
                fact(PlaybackAutoContext.Kernel.MPV,
                        PlaybackAutoContext.ValueSource.PLAYER_MANAGER,
                        PlaybackAutoContext.Confidence.HIGH),
                fact(PlaybackAutoContext.DecodeMode.HARDWARE,
                        PlaybackAutoContext.ValueSource.PLAYBACK_REQUEST,
                        PlaybackAutoContext.Confidence.HIGH),
                device, resource, path, PlaybackAutoContext.RuntimeFacts.unknown(), media);
    }

    private static PlaybackAutoContext withRuntime(
            PlaybackAutoContext context,
            long mediaBitrate,
            PlaybackAutoContext.Confidence confidence,
            long bandwidth,
            boolean expired) {
        PlaybackAutoContext.Fact<Long> media = expired
                ? PlaybackAutoContext.Fact.withTtl(
                mediaBitrate, PlaybackAutoContext.ValueSource.NATIVE_RUNTIME,
                confidence, 0, 10)
                : fact(mediaBitrate, PlaybackAutoContext.ValueSource.NATIVE_RUNTIME, confidence);
        PlaybackAutoContext.RuntimeFacts runtime = new PlaybackAutoContext.RuntimeFacts(
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.PlaybackPhase.UNKNOWN),
                PlaybackAutoContext.Fact.unknown(0L),
                bandwidth > 0
                        ? fact(bandwidth, PlaybackAutoContext.ValueSource.NATIVE_RUNTIME,
                        PlaybackAutoContext.Confidence.HIGH)
                        : PlaybackAutoContext.Fact.unknown(0L),
                media,
                PlaybackAutoContext.Fact.unknown(0f),
                PlaybackAutoContext.Fact.unknown(0L),
                PlaybackAutoContext.Fact.unknown(0));
        return new PlaybackAutoContext(
                context.session(), context.startedAtElapsedMs(), context.revision(),
                context.publishedAtElapsedMs(), context.kernel(), context.decodeMode(),
                context.device(), context.resource(), context.path(), runtime, context.media());
    }

    private static PlaybackAutoContext.TrackFacts track(
            long average,
            long peak,
            PlaybackAutoContext.Confidence confidence) {
        return new PlaybackAutoContext.TrackFacts(
                PlaybackAutoContext.Fact.unknown(""),
                PlaybackAutoContext.Fact.unknown(""),
                PlaybackAutoContext.Fact.unknown(-1),
                PlaybackAutoContext.Fact.unknown(-1),
                PlaybackAutoContext.Fact.unknown(-1),
                PlaybackAutoContext.Fact.unknown(-1),
                PlaybackAutoContext.Fact.unknown(-1f),
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.HdrType.UNKNOWN),
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.ColorSnapshot.unknown()),
                average > 0 ? fact(average, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK, confidence)
                        : PlaybackAutoContext.Fact.unknown(-1L),
                peak > 0 ? fact(peak, PlaybackAutoContext.ValueSource.PLAYER_CALLBACK, confidence)
                        : PlaybackAutoContext.Fact.unknown(-1L));
    }

    private static PlaybackAutoContext.MemorySnapshot memory(
            long heapLimitMib,
            long headroomMib,
            long systemSurplusMib,
            boolean lowRam,
            boolean systemLow) {
        long threshold = 128L * MIB;
        return new PlaybackAutoContext.MemorySnapshot(
                PlaybackAutoContext.MemoryTrigger.PERIODIC,
                Math.max(0, heapLimitMib - headroomMib) * MIB,
                heapLimitMib * MIB,
                headroomMib * MIB,
                lowRam,
                4096L * MIB,
                threshold + systemSurplusMib * MIB,
                threshold,
                systemLow,
                null,
                100,
                64L * MIB);
    }

    private static <T> PlaybackAutoContext.Fact<T> fact(
            T value,
            PlaybackAutoContext.ValueSource source,
            PlaybackAutoContext.Confidence confidence) {
        return PlaybackAutoContext.Fact.untilReplaced(value, source, confidence, 0);
    }
}
