package com.fongmi.android.tv.player.exo;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.TrackGroup;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;

import com.fongmi.android.tv.player.PlaybackAutoContext;

/** Pure policy that selects EXO automatic loading semantics for the current resource. */
final class ExoLoadControlModePolicy {

    static final long HIGH_BITRATE_BITS_PER_SECOND = 50_000_000L;
    static final int SINGLE_TRACK_RESCUE_BUFFER_MS = 3_000;
    private static final int UNKNOWN_COUNT = -1;

    private ExoLoadControlModePolicy() {
    }

    static Decision resolve(
            PlaybackAutoContext.ResourceFacts resourceFacts,
            PlaybackAutoContext.PathFacts pathFacts,
            TrackProfile tracks,
            ExoTargetBufferPolicy.Decision actualTarget,
            ExoTargetBufferPolicy.Decision currentSafety,
            long elapsedRealtimeMs) {
        PlaybackAutoContext.ResourceFacts resource = resourceFacts == null
                ? PlaybackAutoContext.ResourceFacts.unknown() : resourceFacts;
        PlaybackAutoContext.PathFacts path = pathFacts == null
                ? PlaybackAutoContext.PathFacts.unknown() : pathFacts;
        TrackProfile trackProfile = tracks == null ? TrackProfile.unknown() : tracks;
        ExoTargetBufferPolicy.Decision target = actualTarget;
        ExoTargetBufferPolicy.Decision safety = currentSafety == null ? actualTarget : currentSafety;
        long now = Math.max(0, elapsedRealtimeMs);
        PlaybackAutoContext.Protocol protocol = usableValue(
                resource.protocol(), PlaybackAutoContext.Protocol.UNKNOWN, now);
        PlaybackAutoContext.PathKind playerPath = usableValue(
                path.playerPath(), PlaybackAutoContext.PathKind.UNKNOWN, now);
        PlaybackAutoContext.StreamKind streamKind = usableValue(
                resource.streamKind(), PlaybackAutoContext.StreamKind.UNKNOWN, now);
        int manifestVariants = manifestVariantCount(resource.manifest(), now);
        PlaybackAutoContext.MemoryPressure pressure = safety == null
                ? PlaybackAutoContext.MemoryPressure.UNKNOWN : safety.memoryPressure();

        if (protocol == PlaybackAutoContext.Protocol.LOCAL
                || playerPath == PlaybackAutoContext.PathKind.LOCAL) {
            return decision(Mode.LOCAL_TIME, Reason.LOCAL_RESOURCE, protocol, streamKind,
                    trackProfile, manifestVariants, target, safety, 0, -1, 0, false, pressure);
        }

        boolean adaptive = trackProfile.adaptiveVideo() || manifestVariants > 1;
        if (adaptive) {
            return decision(Mode.ADAPTIVE_BYTES, Reason.ADAPTIVE_VIDEO, protocol, streamKind,
                    trackProfile, manifestVariants, target, safety, controlBitrate(target),
                    targetDurationMs(target), 0, false, pressure);
        }
        if (protocol == PlaybackAutoContext.Protocol.HLS
                || protocol == PlaybackAutoContext.Protocol.DASH) {
            return decision(Mode.SEGMENTED_BYTES, Reason.SEGMENTED_PROTOCOL, protocol, streamKind,
                    trackProfile, manifestVariants, target, safety, controlBitrate(target),
                    targetDurationMs(target), 0, false, pressure);
        }
        if (protocol != PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP) {
            return decision(Mode.OTHER_BYTES, Reason.NOT_PROGRESSIVE, protocol, streamKind,
                    trackProfile, manifestVariants, target, safety, controlBitrate(target),
                    targetDurationMs(target), 0, false, pressure);
        }
        if (!trackProfile.hasVideo()) {
            return decision(Mode.PROGRESSIVE_BYTES, Reason.NO_VIDEO, protocol, streamKind,
                    trackProfile, manifestVariants, target, safety, controlBitrate(target),
                    targetDurationMs(target), 0, false, pressure);
        }

        long bitrate = controlBitrate(target);
        if (bitrate <= 0) {
            return decision(Mode.PROGRESSIVE_BYTES, Reason.BITRATE_UNKNOWN, protocol, streamKind,
                    trackProfile, manifestVariants, target, safety, 0, -1, 0, false, pressure);
        }
        long targetDurationMs = targetDurationMs(target);
        if (bitrate < HIGH_BITRATE_BITS_PER_SECOND) {
            return decision(Mode.PROGRESSIVE_BYTES, Reason.BELOW_HIGH_BITRATE, protocol, streamKind,
                    trackProfile, manifestVariants, target, safety, bitrate, targetDurationMs,
                    0, false, pressure);
        }
        if (targetDurationMs >= SINGLE_TRACK_RESCUE_BUFFER_MS) {
            return decision(Mode.PROGRESSIVE_BYTES, Reason.TARGET_SUFFICIENT, protocol, streamKind,
                    trackProfile, manifestVariants, target, safety, bitrate, targetDurationMs,
                    0, false, pressure);
        }

        long rescueBytes = ExoTargetBufferPolicy.bytesForDuration(
                bitrate, SINGLE_TRACK_RESCUE_BUFFER_MS);
        long hardCapacityBytes = hardCapacityBytes(safety);
        boolean hardProtection = hasHardProtection(safety, hardCapacityBytes);
        if (!hardProtection) {
            Reason reason = pressure == PlaybackAutoContext.MemoryPressure.MODERATE
                    || pressure == PlaybackAutoContext.MemoryPressure.CRITICAL
                    ? Reason.MEMORY_PRESSURE : Reason.HARD_PROTECTION_MISSING;
            return decision(Mode.PROGRESSIVE_BYTES, reason, protocol, streamKind,
                    trackProfile, manifestVariants, target, safety, bitrate, targetDurationMs,
                    rescueBytes, false, pressure);
        }
        if (rescueBytes <= 0 || rescueBytes > hardCapacityBytes) {
            return decision(Mode.PROGRESSIVE_BYTES, Reason.HARD_CAPACITY_INSUFFICIENT,
                    protocol, streamKind, trackProfile, manifestVariants, target, safety,
                    bitrate, targetDurationMs, rescueBytes, true, pressure);
        }
        return decision(Mode.PROGRESSIVE_RESCUE_TIME, Reason.CONTROLLED_RESCUE,
                protocol, streamKind, trackProfile, manifestVariants, target, safety,
                bitrate, targetDurationMs, rescueBytes, true, pressure);
    }

    static boolean heapGuardAllows(
            long maxMemoryBytes,
            long totalMemoryBytes,
            long freeMemoryBytes,
            long unusedAllocatorBytes) {
        long maximum = Math.max(0, maxMemoryBytes);
        long total = Math.max(0, totalMemoryBytes);
        if (maximum <= 0 || total < maximum) return true;
        long available = saturatingAdd(Math.max(0, freeMemoryBytes), Math.max(0, unusedAllocatorBytes));
        return available >= maximum / 25L;
    }

    static boolean canAllocate(long allocatedBytes, long allocationSizeBytes, long hardCapacityBytes) {
        long allocated = Math.max(0, allocatedBytes);
        long allocationSize = Math.max(1, allocationSizeBytes);
        long capacity = Math.max(0, hardCapacityBytes);
        return capacity >= allocationSize && allocated <= capacity - allocationSize;
    }

    private static Decision decision(
            Mode mode,
            Reason reason,
            PlaybackAutoContext.Protocol protocol,
            PlaybackAutoContext.StreamKind streamKind,
            TrackProfile tracks,
            int manifestVariants,
            ExoTargetBufferPolicy.Decision actualTarget,
            ExoTargetBufferPolicy.Decision safety,
            long bitrate,
            long targetDurationMs,
            long rescueBytes,
            boolean hardProtection,
            PlaybackAutoContext.MemoryPressure pressure) {
        return new Decision(
                mode,
                reason,
                protocol,
                streamKind,
                tracks,
                manifestVariants,
                bitrate,
                targetDurationMs,
                actualTarget == null ? 0 : actualTarget.targetBytes(),
                rescueBytes,
                hardCapacityBytes(safety),
                hardProtection,
                pressure,
                actualTarget != null
                        && actualTarget.unknownMediaFallback()
                        == ExoTargetBufferPolicy.UnknownMediaFallback.APP_PROXY_VOD);
    }

    private static long controlBitrate(ExoTargetBufferPolicy.Decision target) {
        if (target == null) return 0;
        ExoTargetBufferPolicy.MediaDemand media = target.mediaDemand();
        long average = media.averageReliable() ? media.averageBitsPerSecond() : 0;
        long burst = media.burstReliable() ? media.burstBitsPerSecond() : 0;
        return Math.max(average, burst);
    }

    private static long targetDurationMs(ExoTargetBufferPolicy.Decision target) {
        long bitrate = controlBitrate(target);
        return target == null || bitrate <= 0 ? -1
                : ExoPlaybackDiagnostics.capacityDurationMs(target.targetBytes(), bitrate);
    }

    private static boolean hasHardProtection(
            ExoTargetBufferPolicy.Decision safety,
            long hardCapacityBytes) {
        return safety != null
                && safety.memorySnapshotUsable()
                && safety.memoryPressureUsable()
                && safety.memoryPressure() == PlaybackAutoContext.MemoryPressure.NORMAL
                && safety.javaHeadroomBudgetBytes() >= 0
                && safety.systemBudgetBytes() >= 0
                && safety.heapBudgetBytes() > 0
                && hardCapacityBytes > 0;
    }

    private static long hardCapacityBytes(ExoTargetBufferPolicy.Decision safety) {
        if (safety == null) return 0;
        long capacity = Math.min(
                ExoTargetBufferPolicy.GUARD_TARGET_BYTES,
                Math.max(0, safety.deviceBudgetBytes()));
        if (safety.configuredCapBytes() > 0) {
            capacity = Math.min(capacity, safety.configuredCapBytes());
        }
        return Math.max(0, capacity);
    }

    private static int manifestVariantCount(
            PlaybackAutoContext.Fact<PlaybackAutoContext.ManifestFacts> fact,
            long now) {
        if (fact == null || !fact.isUsable(now)) return UNKNOWN_COUNT;
        Integer count = fact.value().variantCount();
        return count == null || count < 0 ? UNKNOWN_COUNT : count;
    }

    private static <T> T usableValue(PlaybackAutoContext.Fact<T> fact, T fallback, long now) {
        return fact != null && fact.isUsable(now) ? fact.value() : fallback;
    }

    private static long saturatingAdd(long first, long second) {
        return first >= Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second;
    }

    enum Mode {
        LOCAL_TIME("local-time", true),
        ADAPTIVE_BYTES("adaptive-bytes", false),
        SEGMENTED_BYTES("segmented-bytes", false),
        PROGRESSIVE_RESCUE_TIME("progressive-rescue-time", true),
        PROGRESSIVE_BYTES("progressive-bytes", false),
        OTHER_BYTES("other-bytes", false);

        private final String label;
        private final boolean prioritizeTime;

        Mode(String label, boolean prioritizeTime) {
            this.label = label;
            this.prioritizeTime = prioritizeTime;
        }

        String label() {
            return label;
        }

        boolean prioritizeTime() {
            return prioritizeTime;
        }

        boolean controlledTimePriority() {
            return this == PROGRESSIVE_RESCUE_TIME;
        }
    }

    enum Reason {
        LOCAL_RESOURCE("local-resource"),
        ADAPTIVE_VIDEO("adaptive-video"),
        SEGMENTED_PROTOCOL("segmented-protocol"),
        NOT_PROGRESSIVE("not-progressive"),
        NO_VIDEO("no-video"),
        BITRATE_UNKNOWN("bitrate-unknown"),
        BELOW_HIGH_BITRATE("below-high-bitrate"),
        TARGET_SUFFICIENT("target-sufficient"),
        HARD_PROTECTION_MISSING("hard-protection-missing"),
        MEMORY_PRESSURE("memory-pressure"),
        HARD_CAPACITY_INSUFFICIENT("hard-capacity-insufficient"),
        CONTROLLED_RESCUE("controlled-rescue");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    record TrackProfile(
            boolean hasVideo,
            boolean adaptiveVideo,
            int selectedVideoCandidates,
            int availableVideoFormats) {

        TrackProfile {
            selectedVideoCandidates = Math.max(0, selectedVideoCandidates);
            availableVideoFormats = Math.max(0, availableVideoFormats);
            if (!hasVideo) {
                adaptiveVideo = false;
                selectedVideoCandidates = 0;
            }
        }

        static TrackProfile inspect(
                TrackGroupArray trackGroups,
                ExoTrackSelection[] trackSelections) {
            int availableVideoFormats = 0;
            if (trackGroups != null) {
                for (int i = 0; i < trackGroups.length; i++) {
                    TrackGroup group = trackGroups.get(i);
                    if (group != null && group.type == C.TRACK_TYPE_VIDEO) {
                        availableVideoFormats = safeAdd(availableVideoFormats, group.length);
                    }
                }
            }

            boolean hasVideo = false;
            boolean adaptiveVideo = false;
            int selectedVideoCandidates = 0;
            if (trackSelections != null) {
                for (ExoTrackSelection selection : trackSelections) {
                    if (selection == null) continue;
                    TrackGroup group = selection.getTrackGroup();
                    Format selected = selection.getSelectedFormat();
                    if (!isVideo(group, selected)) continue;
                    hasVideo = true;
                    int candidates = Math.max(1, selection.length());
                    selectedVideoCandidates = Math.max(selectedVideoCandidates, candidates);
                    adaptiveVideo |= candidates > 1;
                }
            }
            return new TrackProfile(
                    hasVideo,
                    adaptiveVideo,
                    selectedVideoCandidates,
                    availableVideoFormats);
        }

        static TrackProfile unknown() {
            return new TrackProfile(false, false, 0, 0);
        }

        private static boolean isVideo(TrackGroup group, Format format) {
            if (group != null && group.type == C.TRACK_TYPE_VIDEO) return true;
            if (format == null) return false;
            if (format.width > 0 || format.height > 0) return true;
            return isVideoMime(format.sampleMimeType) || isVideoMime(format.containerMimeType);
        }

        private static boolean isVideoMime(String value) {
            return value != null && value.regionMatches(true, 0, "video/", 0, 6);
        }

        private static int safeAdd(int first, int second) {
            return first > Integer.MAX_VALUE - second ? Integer.MAX_VALUE : first + second;
        }
    }

    record Decision(
            Mode mode,
            Reason reason,
            PlaybackAutoContext.Protocol protocol,
            PlaybackAutoContext.StreamKind streamKind,
            TrackProfile tracks,
            int manifestVariantCount,
            long bitrateBitsPerSecond,
            long targetDurationMs,
            int targetBytes,
            long rescueBytes,
            long hardCapacityBytes,
            boolean hardProtectionAvailable,
            PlaybackAutoContext.MemoryPressure memoryPressure,
            boolean appProxyVodFallback) {

        Decision {
            mode = mode == null ? Mode.OTHER_BYTES : mode;
            reason = reason == null ? Reason.NOT_PROGRESSIVE : reason;
            protocol = protocol == null ? PlaybackAutoContext.Protocol.UNKNOWN : protocol;
            streamKind = streamKind == null ? PlaybackAutoContext.StreamKind.UNKNOWN : streamKind;
            tracks = tracks == null ? TrackProfile.unknown() : tracks;
            bitrateBitsPerSecond = Math.max(0, bitrateBitsPerSecond);
            targetBytes = Math.max(0, targetBytes);
            rescueBytes = Math.max(0, rescueBytes);
            hardCapacityBytes = Math.max(0, hardCapacityBytes);
            memoryPressure = memoryPressure == null
                    ? PlaybackAutoContext.MemoryPressure.UNKNOWN : memoryPressure;
        }

        static Decision unknown() {
            return new Decision(
                    Mode.OTHER_BYTES,
                    Reason.NOT_PROGRESSIVE,
                    PlaybackAutoContext.Protocol.UNKNOWN,
                    PlaybackAutoContext.StreamKind.UNKNOWN,
                    TrackProfile.unknown(),
                    UNKNOWN_COUNT,
                    0,
                    -1,
                    0,
                    0,
                    0,
                    false,
                    PlaybackAutoContext.MemoryPressure.UNKNOWN,
                    false);
        }
    }
}
