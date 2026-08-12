package com.fongmi.android.tv.player.exo;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.TrackGroup;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;

import com.fongmi.android.tv.player.PlaybackAutoContext;

import org.junit.Test;

import java.lang.reflect.Proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExoLoadControlModePolicyTest {

    private static final long NOW = 1_000;
    private static final ExoBufferBudget.Budget FALLBACK = ExoBufferBudget.calculate(
            ExoBufferBudget.MAX_TARGET_BYTES,
            mib(1024),
            false);

    @Test
    public void localResourceUsesIndependentTimePriorityMode() {
        ExoLoadControlModePolicy.Decision decision = resolve(
                resource(PlaybackAutoContext.Protocol.LOCAL, null),
                singleVideo(),
                target(100_000_000L, 0, normalDevice(40)),
                target(100_000_000L, 0, normalDevice(40)));

        assertEquals(ExoLoadControlModePolicy.Mode.LOCAL_TIME, decision.mode());
        assertTrue(decision.mode().prioritizeTime());
        assertFalse(decision.mode().controlledTimePriority());
    }

    @Test
    public void adaptiveVideoAlwaysUsesBytePriority() {
        ExoTargetBufferPolicy.Decision target = target(
                100_000_000L, 0, normalDevice(40));
        ExoLoadControlModePolicy.Decision decision = resolve(
                resource(PlaybackAutoContext.Protocol.HLS, null),
                new ExoLoadControlModePolicy.TrackProfile(true, true, 3, 3),
                target,
                target);

        assertEquals(ExoLoadControlModePolicy.Mode.ADAPTIVE_BYTES, decision.mode());
        assertEquals(ExoLoadControlModePolicy.Reason.ADAPTIVE_VIDEO, decision.reason());
        assertFalse(decision.mode().prioritizeTime());
    }

    @Test
    public void hlsManifestVariantsRemainBytePriorityWhenSelectionIsFixed() {
        ExoTargetBufferPolicy.Decision target = target(
                100_000_000L, 0, normalDevice(40));
        ExoLoadControlModePolicy.Decision decision = resolve(
                resource(PlaybackAutoContext.Protocol.HLS, 4),
                singleVideo(),
                target,
                target);

        assertEquals(ExoLoadControlModePolicy.Mode.ADAPTIVE_BYTES, decision.mode());
        assertEquals(4, decision.manifestVariantCount());
    }

    @Test
    public void singleVariantDashStillUsesSegmentedBytePriority() {
        ExoTargetBufferPolicy.Decision target = target(
                100_000_000L, 0, normalDevice(40));
        ExoLoadControlModePolicy.Decision decision = resolve(
                resource(PlaybackAutoContext.Protocol.DASH, null),
                singleVideo(),
                target,
                target);

        assertEquals(ExoLoadControlModePolicy.Mode.SEGMENTED_BYTES, decision.mode());
        assertEquals(ExoLoadControlModePolicy.Reason.SEGMENTED_PROTOCOL, decision.reason());
    }

    @Test
    public void highBitrateProgressiveUsesControlledRescueWithinHardCapacity() {
        ExoTargetBufferPolicy.Decision target = target(
                100_000_000L, 0, normalDevice(40));

        ExoLoadControlModePolicy.Decision decision = resolve(
                resource(PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP, null),
                singleVideo(),
                target,
                target);

        assertEquals(mib(24), target.targetBytes());
        assertEquals(ExoLoadControlModePolicy.Mode.PROGRESSIVE_RESCUE_TIME, decision.mode());
        assertEquals(ExoLoadControlModePolicy.Reason.CONTROLLED_RESCUE, decision.reason());
        assertTrue(decision.hardProtectionAvailable());
        assertTrue(decision.targetDurationMs() < ExoLoadControlModePolicy.SINGLE_TRACK_RESCUE_BUFFER_MS);
        assertTrue(decision.rescueBytes() <= decision.hardCapacityBytes());
    }

    @Test
    public void missingCurrentMemoryFactsDisableControlledTimePriority() {
        ExoTargetBufferPolicy.Decision actual = target(
                100_000_000L, 0, normalDevice(40));
        ExoTargetBufferPolicy.Decision unknownSafety = target(
                100_000_000L, 0, PlaybackAutoContext.DeviceFacts.unknown());

        ExoLoadControlModePolicy.Decision decision = resolve(
                resource(PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP, null),
                singleVideo(),
                actual,
                unknownSafety);

        assertEquals(ExoLoadControlModePolicy.Mode.PROGRESSIVE_BYTES, decision.mode());
        assertEquals(ExoLoadControlModePolicy.Reason.HARD_PROTECTION_MISSING, decision.reason());
        assertFalse(decision.hardProtectionAvailable());
    }

    @Test
    public void moderateMemoryPressureDisablesControlledTimePriority() {
        ExoTargetBufferPolicy.Decision actual = target(
                100_000_000L, 0, normalDevice(40));
        ExoTargetBufferPolicy.Decision pressured = target(
                100_000_000L, 0, device(40, PlaybackAutoContext.MemoryPressure.MODERATE));

        ExoLoadControlModePolicy.Decision decision = resolve(
                resource(PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP, null),
                singleVideo(),
                actual,
                pressured);

        assertEquals(ExoLoadControlModePolicy.Mode.PROGRESSIVE_BYTES, decision.mode());
        assertEquals(ExoLoadControlModePolicy.Reason.MEMORY_PRESSURE, decision.reason());
    }

    @Test
    public void criticalMemoryPressureDisablesControlledTimePriority() {
        ExoTargetBufferPolicy.Decision actual = target(
                100_000_000L, 0, normalDevice(40));
        ExoTargetBufferPolicy.Decision pressured = target(
                100_000_000L, 0, device(40, PlaybackAutoContext.MemoryPressure.CRITICAL));

        ExoLoadControlModePolicy.Decision decision = resolve(
                resource(PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP, null),
                singleVideo(),
                actual,
                pressured);

        assertEquals(ExoLoadControlModePolicy.Mode.PROGRESSIVE_BYTES, decision.mode());
        assertEquals(ExoLoadControlModePolicy.Reason.MEMORY_PRESSURE, decision.reason());
    }

    @Test
    public void expiredMemoryFactsDisableControlledTimePriority() {
        ExoTargetBufferPolicy.Decision actual = target(
                100_000_000L, 0, normalDevice(40));
        ExoTargetBufferPolicy.Decision expired = target(
                100_000_000L, 0, staleDevice(40));

        ExoLoadControlModePolicy.Decision decision = resolve(
                resource(PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP, null),
                singleVideo(),
                actual,
                expired);

        assertEquals(ExoLoadControlModePolicy.Mode.PROGRESSIVE_BYTES, decision.mode());
        assertEquals(ExoLoadControlModePolicy.Reason.HARD_PROTECTION_MISSING, decision.reason());
        assertFalse(decision.hardProtectionAvailable());
    }

    @Test
    public void exactCapacityMustFitWholeRescueWindow() {
        ExoTargetBufferPolicy.Decision actual = target(
                100_000_000L, 0, normalDevice(32));

        ExoLoadControlModePolicy.Decision decision = resolve(
                resource(PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP, null),
                singleVideo(),
                actual,
                actual);

        assertEquals(mib(24), actual.targetBytes());
        assertEquals(ExoLoadControlModePolicy.Mode.PROGRESSIVE_BYTES, decision.mode());
        assertEquals(ExoLoadControlModePolicy.Reason.HARD_CAPACITY_INSUFFICIENT, decision.reason());
    }

    @Test
    public void explicitTargetCapIsNotBypassedByRescue() {
        ExoTargetBufferPolicy.Decision capped = target(
                100_000_000L, mib(24), normalDevice(40));

        ExoLoadControlModePolicy.Decision decision = resolve(
                resource(PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP, null),
                singleVideo(),
                capped,
                capped);

        assertEquals(ExoLoadControlModePolicy.Mode.PROGRESSIVE_BYTES, decision.mode());
        assertEquals(ExoLoadControlModePolicy.Reason.HARD_CAPACITY_INSUFFICIENT, decision.reason());
        assertEquals(mib(24), decision.hardCapacityBytes());
    }

    @Test
    public void sufficientTargetDoesNotEnableTimePriority() {
        ExoTargetBufferPolicy.Decision target = target(
                100_000_000L, 0, normalDevice(64));

        ExoLoadControlModePolicy.Decision decision = resolve(
                resource(PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP, null),
                singleVideo(),
                target,
                target);

        assertEquals(ExoLoadControlModePolicy.Mode.PROGRESSIVE_BYTES, decision.mode());
        assertEquals(ExoLoadControlModePolicy.Reason.TARGET_SUFFICIENT, decision.reason());
    }

    @Test
    public void ordinaryProgressiveAndUnknownResourcesStayBytePriority() {
        ExoTargetBufferPolicy.Decision ordinary = target(
                20_000_000L, 0, normalDevice(40));
        ExoLoadControlModePolicy.Decision progressive = resolve(
                resource(PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP, null),
                singleVideo(),
                ordinary,
                ordinary);
        ExoLoadControlModePolicy.Decision unknown = resolve(
                PlaybackAutoContext.ResourceFacts.unknown(),
                singleVideo(),
                ordinary,
                ordinary);

        assertEquals(ExoLoadControlModePolicy.Mode.PROGRESSIVE_BYTES, progressive.mode());
        assertEquals(ExoLoadControlModePolicy.Mode.OTHER_BYTES, unknown.mode());
        assertFalse(progressive.mode().prioritizeTime());
        assertFalse(unknown.mode().prioritizeTime());
    }

    @Test
    public void unknownAppProxyVodFallbackIsCarriedIntoRecoveryMode() {
        ExoTargetBufferPolicy.Decision target = ExoTargetBufferPolicy.resolve(
                ExoTargetBufferPolicy.MediaDemand.unknown(),
                0,
                FALLBACK,
                ExoTargetBufferPolicy.UnknownMediaFallback.APP_PROXY_VOD,
                normalDevice(128),
                NOW);

        ExoLoadControlModePolicy.Decision decision = resolve(
                PlaybackAutoContext.ResourceFacts.unknown(),
                singleVideo(),
                target,
                target);

        assertEquals(mib(96), target.targetBytes());
        assertTrue(decision.appProxyVodFallback());
        assertEquals(ExoLoadControlModePolicy.Mode.OTHER_BYTES, decision.mode());
    }

    @Test
    public void audioOnlyProgressiveDoesNotUseVideoRescue() {
        ExoTargetBufferPolicy.Decision target = target(
                100_000_000L, 0, normalDevice(40));

        ExoLoadControlModePolicy.Decision decision = resolve(
                resource(PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP, null),
                ExoLoadControlModePolicy.TrackProfile.unknown(),
                target,
                target);

        assertEquals(ExoLoadControlModePolicy.Mode.PROGRESSIVE_BYTES, decision.mode());
        assertEquals(ExoLoadControlModePolicy.Reason.NO_VIDEO, decision.reason());
    }

    @Test
    public void trackInspectionDistinguishesAdaptiveVideoFromAudio() {
        Format high = new Format.Builder().setSampleMimeType("video/avc").setAverageBitrate(10_000_000).build();
        Format audio = new Format.Builder().setSampleMimeType("audio/mp4a-latm").setAverageBitrate(192_000).build();
        ExoLoadControlModePolicy.TrackProfile profile =
                ExoLoadControlModePolicy.TrackProfile.inspect(
                        TrackGroupArray.EMPTY,
                        new ExoTrackSelection[]{selection(null, 2, high), selection(null, 1, audio)});

        assertTrue(profile.hasVideo());
        assertTrue(profile.adaptiveVideo());
        assertEquals(2, profile.selectedVideoCandidates());
        assertEquals(0, profile.availableVideoFormats());
    }

    @Test
    public void media3HeapGuardAndHardCapacityBoundariesArePreserved() {
        assertTrue(ExoLoadControlModePolicy.heapGuardAllows(100, 99, 0, 0));
        assertFalse(ExoLoadControlModePolicy.heapGuardAllows(100, 100, 3, 0));
        assertTrue(ExoLoadControlModePolicy.heapGuardAllows(100, 100, 3, 1));

        assertTrue(ExoLoadControlModePolicy.canAllocate(30, 2, 32));
        assertFalse(ExoLoadControlModePolicy.canAllocate(31, 2, 32));
        assertFalse(ExoLoadControlModePolicy.canAllocate(0, 64, 32));
    }

    private static ExoLoadControlModePolicy.Decision resolve(
            PlaybackAutoContext.ResourceFacts resource,
            ExoLoadControlModePolicy.TrackProfile tracks,
            ExoTargetBufferPolicy.Decision actual,
            ExoTargetBufferPolicy.Decision safety) {
        return ExoLoadControlModePolicy.resolve(
                resource,
                PlaybackAutoContext.PathFacts.unknown(),
                tracks,
                actual,
                safety,
                NOW);
    }

    private static PlaybackAutoContext.ResourceFacts resource(
            PlaybackAutoContext.Protocol protocol,
            Integer variants) {
        PlaybackAutoContext.Fact<PlaybackAutoContext.ManifestFacts> manifest = variants == null
                ? PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.ManifestFacts.unknown())
                : PlaybackAutoContext.Fact.withTtl(
                        new PlaybackAutoContext.ManifestFacts(
                                PlaybackAutoContext.ManifestKind.HLS_MASTER,
                                null,
                                null,
                                null,
                                null,
                                variants,
                                false,
                                false),
                        PlaybackAutoContext.ValueSource.MANIFEST,
                        PlaybackAutoContext.Confidence.HIGH,
                        NOW,
                        60_000);
        return new PlaybackAutoContext.ResourceFacts(
                PlaybackAutoContext.Fact.forSession(
                        protocol,
                        PlaybackAutoContext.ValueSource.PLAYBACK_REQUEST,
                        PlaybackAutoContext.Confidence.HIGH,
                        NOW),
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.StreamKind.UNKNOWN),
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.RangeSupport.UNKNOWN),
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.TransferUnit.UNKNOWN),
                manifest);
    }

    private static ExoLoadControlModePolicy.TrackProfile singleVideo() {
        return new ExoLoadControlModePolicy.TrackProfile(true, false, 1, 1);
    }

    private static ExoTargetBufferPolicy.Decision target(
            long bitrate,
            int configuredCap,
            PlaybackAutoContext.DeviceFacts device) {
        ExoTargetBufferPolicy.MediaDemand demand = new ExoTargetBufferPolicy.MediaDemand(
                bitrate,
                ExoTargetBufferPolicy.DemandSource.SELECTED_TRACK,
                PlaybackAutoContext.Confidence.MEDIUM,
                bitrate,
                ExoTargetBufferPolicy.DemandSource.SELECTED_TRACK,
                PlaybackAutoContext.Confidence.MEDIUM);
        return ExoTargetBufferPolicy.resolve(demand, configuredCap, FALLBACK, device, NOW);
    }

    private static PlaybackAutoContext.DeviceFacts normalDevice(int exactBudgetMiB) {
        return device(exactBudgetMiB, PlaybackAutoContext.MemoryPressure.NORMAL);
    }

    private static PlaybackAutoContext.DeviceFacts staleDevice(int exactBudgetMiB) {
        long budget = mib(exactBudgetMiB);
        long reserve = mib(64);
        long heapLimit = mib(1024);
        long headroom = budget + reserve;
        PlaybackAutoContext.MemorySnapshot snapshot = new PlaybackAutoContext.MemorySnapshot(
                PlaybackAutoContext.MemoryTrigger.SESSION_START,
                heapLimit - headroom,
                heapLimit,
                headroom,
                false,
                mibLong(2048),
                mib(1024) + budget,
                mibLong(1024),
                false,
                null,
                100,
                mibLong(32));
        return new PlaybackAutoContext.DeviceFacts(
                PlaybackAutoContext.Fact.withTtl(
                        PlaybackAutoContext.MemoryPressure.NORMAL,
                        PlaybackAutoContext.ValueSource.SYSTEM_API,
                        PlaybackAutoContext.Confidence.HIGH,
                        0,
                        100),
                PlaybackAutoContext.Fact.withTtl(
                        snapshot,
                        PlaybackAutoContext.ValueSource.SYSTEM_API,
                        PlaybackAutoContext.Confidence.HIGH,
                        0,
                        100),
                PlaybackAutoContext.Fact.unknown(-1L),
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.ThermalState.UNKNOWN),
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.PowerState.UNKNOWN),
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.NetworkCost.UNKNOWN),
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.NetworkSnapshot.unknown()));
    }

    private static PlaybackAutoContext.DeviceFacts device(
            int exactBudgetMiB,
            PlaybackAutoContext.MemoryPressure pressure) {
        long budget = mib(exactBudgetMiB);
        long reserve = mib(64);
        long heapLimit = mib(1024);
        long headroom = budget + reserve;
        PlaybackAutoContext.MemorySnapshot snapshot = new PlaybackAutoContext.MemorySnapshot(
                PlaybackAutoContext.MemoryTrigger.SESSION_START,
                heapLimit - headroom,
                heapLimit,
                headroom,
                false,
                mibLong(2048),
                mib(1024) + budget,
                mibLong(1024),
                false,
                null,
                100,
                mibLong(32));
        return new PlaybackAutoContext.DeviceFacts(
                PlaybackAutoContext.Fact.withTtl(
                        pressure,
                        PlaybackAutoContext.ValueSource.SYSTEM_API,
                        PlaybackAutoContext.Confidence.HIGH,
                        NOW,
                        60_000),
                PlaybackAutoContext.Fact.withTtl(
                        snapshot,
                        PlaybackAutoContext.ValueSource.SYSTEM_API,
                        PlaybackAutoContext.Confidence.HIGH,
                        NOW,
                        60_000),
                PlaybackAutoContext.Fact.unknown(-1L),
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.ThermalState.UNKNOWN),
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.PowerState.UNKNOWN),
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.NetworkCost.UNKNOWN),
                PlaybackAutoContext.Fact.unknown(PlaybackAutoContext.NetworkSnapshot.unknown()));
    }

    private static ExoTrackSelection selection(
            TrackGroup group,
            int length,
            Format selected) {
        return (ExoTrackSelection) Proxy.newProxyInstance(
                ExoTrackSelection.class.getClassLoader(),
                new Class<?>[]{ExoTrackSelection.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getTrackGroup" -> group;
                    case "getSelectedFormat" -> selected;
                    case "length" -> length;
                    case "getFormat" -> group == null ? selected : group.getFormat((Integer) args[0]);
                    case "toString" -> "selection";
                    default -> defaultValue(method.getReturnType());
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == char.class) return '\0';
        return null;
    }

    private static int mib(int value) {
        return value * 1024 * 1024;
    }

    private static long mibLong(int value) {
        return (long) value * 1024L * 1024L;
    }
}
