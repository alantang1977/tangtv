package com.fongmi.android.tv.player.mpv;

import com.fongmi.android.tv.player.PlaybackAutoContext;

/** Pure demand and capacity policy for MPV automatic forward-cache control. */
public final class MpvForwardCachePolicy {

    public static final long MIB = 1024L * 1024L;
    public static final long MIN_FORWARD_BYTES = 24L * MIB;
    public static final long CONSERVATIVE_FORWARD_BYTES = 48L * MIB;
    public static final long BALANCED_FORWARD_BYTES = 64L * MIB;
    public static final long LARGE_FORWARD_BYTES = 96L * MIB;
    public static final long VERY_LARGE_FORWARD_BYTES = 128L * MIB;
    public static final long MAX_FORWARD_BYTES = 192L * MIB;
    public static final long TARGET_DURATION_MS = 30_000L;
    public static final long BURST_DURATION_MS = 10_000L;
    public static final int AVERAGE_HEADROOM_PERCENT = 115;

    private static final long LOW_HEADROOM_BYTES = 64L * MIB;
    private static final long LOW_SYSTEM_SURPLUS_BYTES = 128L * MIB;
    private static final long SMALL_HEAP_BYTES = 192L * MIB;
    private static final long[] FORWARD_TIERS = {
            MIN_FORWARD_BYTES,
            CONSERVATIVE_FORWARD_BYTES,
            BALANCED_FORWARD_BYTES,
            LARGE_FORWARD_BYTES,
            VERY_LARGE_FORWARD_BYTES,
            MAX_FORWARD_BYTES
    };

    private MpvForwardCachePolicy() {
    }

    public static Assessment assess(
            PlaybackAutoContext context,
            boolean automatic,
            boolean mpv,
            boolean performancePriority,
            long initialBaselineBytes,
            long nowElapsedMs) {
        PlaybackAutoContext current = context == null ? PlaybackAutoContext.empty() : context;
        long now = Math.max(0, nowElapsedMs);
        long baseline = normalizeTier(initialBaselineBytes);
        if (!automatic || !mpv) {
            return Assessment.inactive(baseline, Reason.NOT_AUTOMATIC_MPV);
        }
        if (!performancePriority) {
            return Assessment.inactive(baseline, Reason.CONFIG_PRIORITY);
        }

        BitrateEvidence average = formatAverage(current.media(), now);
        if (!average.reliable()) {
            average = runtimeAverage(current.runtime().mediaBitrateBitsPerSecond(), now);
        }
        BitrateEvidence peak = formatPeak(current.media(), now);
        long averageDemand = average.reliable()
                ? percent(bytesForDuration(average.bitsPerSecond(), TARGET_DURATION_MS),
                AVERAGE_HEADROOM_PERCENT) : 0;
        long burstDemand = peak.reliable()
                ? bytesForDuration(peak.bitsPerSecond(), BURST_DURATION_MS) : 0;
        long payloadDemand = Math.max(averageDemand, burstDemand);
        boolean mediaReliable = average.reliable() || peak.reliable();
        long mediaTarget = mediaReliable ? tierForDemand(payloadDemand) : -1;

        Capacity capacity = capacity(current.device(), baseline, now);
        ResourceLimit resource = resourceLimit(current, now);
        long safeTarget = Math.min(capacity.targetBytes(), resource.targetBytes());
        Reason reason = capacity.reason();
        if (resource.targetBytes() < capacity.targetBytes()) reason = resource.reason();
        if (mediaReliable && mediaTarget <= safeTarget) reason = Reason.MEDIA_DEMAND;

        return new Assessment(
                true,
                Reason.ACTIVE,
                baseline,
                average,
                peak,
                averageDemand,
                burstDemand,
                payloadDemand,
                mediaReliable,
                mediaTarget,
                capacity.targetBytes(),
                resource.targetBytes(),
                safeTarget,
                capacity.pressure(),
                capacity.pressureUsable(),
                capacity.snapshotUsable(),
                capacity.memorySampleAtElapsedMs(),
                capacity.critical(),
                reason);
    }

    public static long tierForDemand(long demandBytes) {
        long demand = Math.max(0, demandBytes);
        for (long tier : FORWARD_TIERS) {
            if (demand <= tier) return tier;
        }
        return MAX_FORWARD_BYTES;
    }

    public static long tierForCapacity(long capacityBytes) {
        long capacity = Math.max(0, capacityBytes);
        long selected = MIN_FORWARD_BYTES;
        for (long tier : FORWARD_TIERS) {
            if (tier > capacity) break;
            selected = tier;
        }
        return selected;
    }

    public static long normalizeTier(long targetBytes) {
        if (targetBytes <= 0) return MIN_FORWARD_BYTES;
        return tierForCapacity(Math.min(MAX_FORWARD_BYTES, targetBytes));
    }

    public static long previousTier(long targetBytes) {
        long normalized = normalizeTier(targetBytes);
        long previous = MIN_FORWARD_BYTES;
        for (long tier : FORWARD_TIERS) {
            if (tier >= normalized) return previous;
            previous = tier;
        }
        return previous;
    }

    public static long nextTier(long targetBytes, long ceilingBytes) {
        long current = normalizeTier(targetBytes);
        long ceiling = normalizeTier(Math.max(MIN_FORWARD_BYTES, ceilingBytes));
        if (current >= ceiling) return ceiling;
        for (long tier : FORWARD_TIERS) {
            if (tier > current) return Math.min(tier, ceiling);
        }
        return ceiling;
    }

    public static long bytesForDuration(long bitrateBitsPerSecond, long durationMs) {
        return multiplyDivide(bitrateBitsPerSecond, durationMs, 8_000L);
    }

    private static BitrateEvidence formatAverage(
            PlaybackAutoContext.MediaFacts media,
            long nowElapsedMs) {
        PlaybackAutoContext.MediaFacts current = media == null
                ? PlaybackAutoContext.MediaFacts.unknown() : media;
        return combine(
                current.videoTrack().averageBitrateBitsPerSecond(),
                current.audioTrack().averageBitrateBitsPerSecond(),
                nowElapsedMs,
                Source.TRACK_FORMAT,
                false);
    }

    private static BitrateEvidence formatPeak(
            PlaybackAutoContext.MediaFacts media,
            long nowElapsedMs) {
        PlaybackAutoContext.MediaFacts current = media == null
                ? PlaybackAutoContext.MediaFacts.unknown() : media;
        PlaybackAutoContext.Fact<Long> videoPeak =
                current.videoTrack().peakBitrateBitsPerSecond();
        if (!usablePositive(videoPeak, nowElapsedMs)) return BitrateEvidence.unknown();
        PlaybackAutoContext.Fact<Long> audioPeak =
                current.audioTrack().peakBitrateBitsPerSecond();
        PlaybackAutoContext.Fact<Long> audioAverage =
                current.audioTrack().averageBitrateBitsPerSecond();
        PlaybackAutoContext.Fact<Long> audio = usablePositive(audioPeak, nowElapsedMs)
                ? audioPeak : audioAverage;
        return combine(videoPeak, audio, nowElapsedMs, Source.TRACK_FORMAT, true);
    }

    private static BitrateEvidence runtimeAverage(
            PlaybackAutoContext.Fact<Long> fact,
            long nowElapsedMs) {
        if (!usablePositive(fact, nowElapsedMs)) return BitrateEvidence.unknown();
        return new BitrateEvidence(
                fact.value(),
                Source.RUNTIME_MEDIA,
                fact.source(),
                fact.confidence());
    }

    private static BitrateEvidence combine(
            PlaybackAutoContext.Fact<Long> primary,
            PlaybackAutoContext.Fact<Long> secondary,
            long nowElapsedMs,
            Source source,
            boolean requirePrimary) {
        boolean primaryUsable = usablePositive(primary, nowElapsedMs);
        boolean secondaryUsable = usablePositive(secondary, nowElapsedMs);
        if ((requirePrimary && !primaryUsable) || (!primaryUsable && !secondaryUsable)) {
            return BitrateEvidence.unknown();
        }
        long value = 0;
        PlaybackAutoContext.Confidence confidence = PlaybackAutoContext.Confidence.HIGH;
        PlaybackAutoContext.ValueSource valueSource = PlaybackAutoContext.ValueSource.PLAYER_CALLBACK;
        if (primaryUsable) {
            value = saturatingAdd(value, primary.value());
            confidence = lowerConfidence(confidence, primary.confidence());
            valueSource = primary.source();
        }
        if (secondaryUsable) {
            value = saturatingAdd(value, secondary.value());
            confidence = lowerConfidence(confidence, secondary.confidence());
            if (!primaryUsable) valueSource = secondary.source();
        }
        return new BitrateEvidence(value, source, valueSource, confidence);
    }

    private static boolean usablePositive(
            PlaybackAutoContext.Fact<Long> fact,
            long nowElapsedMs) {
        return fact != null
                && fact.isUsable(nowElapsedMs)
                && fact.value() > 0
                && fact.confidence() != PlaybackAutoContext.Confidence.LOW
                && fact.confidence() != PlaybackAutoContext.Confidence.UNKNOWN;
    }

    private static Capacity capacity(
            PlaybackAutoContext.DeviceFacts deviceFacts,
            long baselineBytes,
            long nowElapsedMs) {
        PlaybackAutoContext.DeviceFacts device = deviceFacts == null
                ? PlaybackAutoContext.DeviceFacts.unknown() : deviceFacts;
        PlaybackAutoContext.Fact<PlaybackAutoContext.MemoryPressure> pressureFact =
                device.memoryPressure();
        PlaybackAutoContext.Fact<PlaybackAutoContext.MemorySnapshot> snapshotFact =
                device.memorySnapshot();
        boolean pressureUsable = pressureFact.isUsable(nowElapsedMs);
        boolean snapshotUsable = snapshotFact.isUsable(nowElapsedMs)
                && snapshotFact.value().hasEvidence();
        PlaybackAutoContext.MemoryPressure pressure = pressureUsable
                ? pressureFact.value() : PlaybackAutoContext.MemoryPressure.UNKNOWN;
        long memorySampleAt = Math.max(
                pressureUsable ? pressureFact.sampledAtElapsedMs() : -1,
                snapshotUsable ? snapshotFact.sampledAtElapsedMs() : -1);
        PlaybackAutoContext.MemorySnapshot snapshot = snapshotUsable
                ? snapshotFact.value() : PlaybackAutoContext.MemorySnapshot.unknown();

        boolean critical = pressure == PlaybackAutoContext.MemoryPressure.CRITICAL
                || Boolean.TRUE.equals(snapshot.systemLowMemory())
                || atOrBelow(snapshot.systemAvailableBytes(), snapshot.systemThresholdBytes())
                || Long.valueOf(0).equals(snapshot.javaHeapHeadroomBytes());
        if (critical) {
            return new Capacity(MIN_FORWARD_BYTES, pressure, pressureUsable,
                    snapshotUsable, memorySampleAt, true, Reason.CRITICAL_MEMORY);
        }
        if (!snapshotUsable) {
            return new Capacity(baselineBytes, pressure, pressureUsable,
                    false, memorySampleAt, false, Reason.MEMORY_UNKNOWN);
        }

        Long heapLimit = snapshot.javaHeapLimitBytes();
        Long headroom = snapshot.javaHeapHeadroomBytes();
        Long surplus = systemSurplus(snapshot);
        if ((headroom != null && headroom <= LOW_HEADROOM_BYTES)
                || (surplus != null && surplus <= LOW_SYSTEM_SURPLUS_BYTES)) {
            return new Capacity(MIN_FORWARD_BYTES, pressure, pressureUsable,
                    true, memorySampleAt, false, Reason.LOW_HEADROOM);
        }

        long target = CONSERVATIVE_FORWARD_BYTES;
        Reason reason = Reason.CONSERVATIVE_CAPACITY;
        boolean lowRam = Boolean.TRUE.equals(snapshot.lowRamDevice());
        if (!lowRam && (heapLimit == null || heapLimit > SMALL_HEAP_BYTES)
                && meets(heapLimit, 256L * MIB, headroom, 128L * MIB,
                surplus, 512L * MIB)) {
            target = BALANCED_FORWARD_BYTES;
            reason = Reason.BALANCED_CAPACITY;
        }
        if (!lowRam && meets(heapLimit, 384L * MIB, headroom, 192L * MIB,
                surplus, 768L * MIB)) {
            target = LARGE_FORWARD_BYTES;
            reason = Reason.LARGE_CAPACITY;
        }
        if (!lowRam && meets(heapLimit, 512L * MIB, headroom, 256L * MIB,
                surplus, 1024L * MIB)) {
            target = VERY_LARGE_FORWARD_BYTES;
            reason = Reason.VERY_LARGE_CAPACITY;
        }
        if (!lowRam && meets(heapLimit, 512L * MIB, headroom, 384L * MIB,
                surplus, 1536L * MIB)) {
            target = MAX_FORWARD_BYTES;
            reason = Reason.MAX_CAPACITY;
        }
        if (lowRam || (heapLimit != null && heapLimit <= SMALL_HEAP_BYTES)) {
            target = Math.min(target, CONSERVATIVE_FORWARD_BYTES);
            reason = Reason.LOW_RAM_OR_SMALL_HEAP;
        }
        return new Capacity(target, pressure, pressureUsable,
                true, memorySampleAt, false, reason);
    }

    private static ResourceLimit resourceLimit(
            PlaybackAutoContext context,
            long nowElapsedMs) {
        PlaybackAutoContext.ResourceFacts resource = context.resource();
        PlaybackAutoContext.PathFacts path = context.path();
        PlaybackAutoContext.Fact<PlaybackAutoContext.Protocol> protocol = resource.protocol();
        PlaybackAutoContext.Fact<PlaybackAutoContext.StreamKind> stream = resource.streamKind();
        PlaybackAutoContext.Fact<PlaybackAutoContext.PathKind> playerPath = path.playerPath();
        PlaybackAutoContext.Fact<PlaybackAutoContext.PathKind> upstreamPath = path.upstreamPath();
        PlaybackAutoContext.Fact<PlaybackAutoContext.UpstreamState> upstreamState = path.upstreamState();
        if (isUsable(protocol, nowElapsedMs, PlaybackAutoContext.Protocol.LOCAL)
                || isUsable(playerPath, nowElapsedMs, PlaybackAutoContext.PathKind.LOCAL)
                || isUsable(upstreamPath, nowElapsedMs, PlaybackAutoContext.PathKind.LOCAL)) {
            return new ResourceLimit(MIN_FORWARD_BYTES, Reason.LOCAL_RESOURCE_CAP);
        }
        if (isUsable(stream, nowElapsedMs, PlaybackAutoContext.StreamKind.LIVE)
                || isUsable(stream, nowElapsedMs, PlaybackAutoContext.StreamKind.LOW_LATENCY_LIVE)
                || isUsable(protocol, nowElapsedMs, PlaybackAutoContext.Protocol.RTSP)
                || isUsable(protocol, nowElapsedMs, PlaybackAutoContext.Protocol.RTMP)) {
            return new ResourceLimit(CONSERVATIVE_FORWARD_BYTES, Reason.LIVE_RESOURCE_CAP);
        }
        if (isUsable(playerPath, nowElapsedMs, PlaybackAutoContext.PathKind.EXTERNAL_LOOPBACK)
                || isUsable(upstreamState, nowElapsedMs, PlaybackAutoContext.UpstreamState.OPAQUE)) {
            return new ResourceLimit(CONSERVATIVE_FORWARD_BYTES, Reason.OPAQUE_RESOURCE_CAP);
        }
        return new ResourceLimit(MAX_FORWARD_BYTES, Reason.REMOTE_VOD_CAP);
    }

    private static <T> boolean isUsable(
            PlaybackAutoContext.Fact<T> fact,
            long nowElapsedMs,
            T expected) {
        return fact != null && fact.isUsable(nowElapsedMs) && expected.equals(fact.value());
    }

    private static Long systemSurplus(PlaybackAutoContext.MemorySnapshot snapshot) {
        Long available = snapshot.systemAvailableBytes();
        Long threshold = snapshot.systemThresholdBytes();
        if (available == null || threshold == null) return null;
        return Math.max(0, available - threshold);
    }

    private static boolean atOrBelow(Long value, Long threshold) {
        return value != null && threshold != null && value <= threshold;
    }

    private static boolean meets(
            Long heapLimit,
            long minimumHeap,
            Long headroom,
            long minimumHeadroom,
            Long surplus,
            long minimumSurplus) {
        return heapLimit != null && heapLimit >= minimumHeap
                && headroom != null && headroom >= minimumHeadroom
                && surplus != null && surplus >= minimumSurplus;
    }

    private static PlaybackAutoContext.Confidence lowerConfidence(
            PlaybackAutoContext.Confidence first,
            PlaybackAutoContext.Confidence second) {
        return confidenceRank(first) <= confidenceRank(second) ? first : second;
    }

    private static int confidenceRank(PlaybackAutoContext.Confidence confidence) {
        if (confidence == null) return 0;
        return switch (confidence) {
            case UNKNOWN -> 0;
            case LOW -> 1;
            case MEDIUM -> 2;
            case HIGH -> 3;
        };
    }

    private static long percent(long value, int percent) {
        return multiplyDivide(value, percent, 100L);
    }

    private static long multiplyDivide(long value, long multiplier, long divisor) {
        if (value <= 0 || multiplier <= 0 || divisor <= 0) return 0;
        long whole = value / divisor;
        long remainder = value % divisor;
        return saturatingAdd(
                saturatingMultiply(whole, multiplier),
                saturatingMultiply(remainder, multiplier) / divisor);
    }

    private static long saturatingMultiply(long first, long second) {
        if (first <= 0 || second <= 0) return 0;
        return first > Long.MAX_VALUE / second ? Long.MAX_VALUE : first * second;
    }

    private static long saturatingAdd(long first, long second) {
        if (first >= Long.MAX_VALUE - second) return Long.MAX_VALUE;
        return first + second;
    }

    public enum Source {
        TRACK_FORMAT("track-format"),
        RUNTIME_MEDIA("runtime-media"),
        UNKNOWN("unknown");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Reason {
        NOT_AUTOMATIC_MPV("not-automatic-mpv"),
        CONFIG_PRIORITY("mpv-conf-priority"),
        ACTIVE("active"),
        MEDIA_DEMAND("media-demand"),
        CRITICAL_MEMORY("critical-memory"),
        MEMORY_UNKNOWN("memory-unknown"),
        LOW_HEADROOM("low-headroom"),
        LOW_RAM_OR_SMALL_HEAP("low-ram-or-small-heap"),
        CONSERVATIVE_CAPACITY("conservative-capacity"),
        BALANCED_CAPACITY("balanced-capacity"),
        LARGE_CAPACITY("large-capacity"),
        VERY_LARGE_CAPACITY("very-large-capacity"),
        MAX_CAPACITY("max-capacity"),
        LOCAL_RESOURCE_CAP("local-resource-cap"),
        LIVE_RESOURCE_CAP("live-resource-cap"),
        OPAQUE_RESOURCE_CAP("opaque-resource-cap"),
        REMOTE_VOD_CAP("remote-vod-cap");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record BitrateEvidence(
            long bitsPerSecond,
            Source source,
            PlaybackAutoContext.ValueSource valueSource,
            PlaybackAutoContext.Confidence confidence) {

        public BitrateEvidence {
            bitsPerSecond = Math.max(0, bitsPerSecond);
            source = source == null ? Source.UNKNOWN : source;
            valueSource = valueSource == null
                    ? PlaybackAutoContext.ValueSource.UNKNOWN : valueSource;
            confidence = confidence == null
                    ? PlaybackAutoContext.Confidence.UNKNOWN : confidence;
        }

        public static BitrateEvidence unknown() {
            return new BitrateEvidence(0, Source.UNKNOWN,
                    PlaybackAutoContext.ValueSource.UNKNOWN,
                    PlaybackAutoContext.Confidence.UNKNOWN);
        }

        public boolean reliable() {
            return bitsPerSecond > 0
                    && source != Source.UNKNOWN
                    && valueSource != PlaybackAutoContext.ValueSource.UNKNOWN
                    && confidence != PlaybackAutoContext.Confidence.UNKNOWN
                    && confidence != PlaybackAutoContext.Confidence.LOW;
        }
    }

    public record Assessment(
            boolean active,
            Reason inactiveReason,
            long initialBaselineBytes,
            BitrateEvidence averageBitrate,
            BitrateEvidence peakBitrate,
            long averageDemandBytes,
            long burstDemandBytes,
            long payloadDemandBytes,
            boolean mediaReliable,
            long mediaTargetBytes,
            long capacityTargetBytes,
            long resourceTargetBytes,
            long safeTargetBytes,
            PlaybackAutoContext.MemoryPressure memoryPressure,
            boolean pressureUsable,
            boolean snapshotUsable,
            long memorySampleAtElapsedMs,
            boolean critical,
            Reason limitingReason) {

        public Assessment {
            inactiveReason = inactiveReason == null ? Reason.ACTIVE : inactiveReason;
            initialBaselineBytes = normalizeTier(initialBaselineBytes);
            averageBitrate = averageBitrate == null ? BitrateEvidence.unknown() : averageBitrate;
            peakBitrate = peakBitrate == null ? BitrateEvidence.unknown() : peakBitrate;
            averageDemandBytes = Math.max(0, averageDemandBytes);
            burstDemandBytes = Math.max(0, burstDemandBytes);
            payloadDemandBytes = Math.max(0, payloadDemandBytes);
            mediaTargetBytes = mediaReliable ? normalizeTier(mediaTargetBytes) : -1;
            capacityTargetBytes = normalizeTier(capacityTargetBytes);
            resourceTargetBytes = normalizeTier(resourceTargetBytes);
            safeTargetBytes = normalizeTier(Math.min(capacityTargetBytes, resourceTargetBytes));
            memoryPressure = memoryPressure == null
                    ? PlaybackAutoContext.MemoryPressure.UNKNOWN : memoryPressure;
            memorySampleAtElapsedMs = memorySampleAtElapsedMs < 0 ? -1 : memorySampleAtElapsedMs;
            limitingReason = limitingReason == null ? Reason.MEMORY_UNKNOWN : limitingReason;
        }

        static Assessment inactive(long baselineBytes, Reason reason) {
            return new Assessment(false, reason, baselineBytes,
                    BitrateEvidence.unknown(), BitrateEvidence.unknown(),
                    0, 0, 0, false, -1,
                    baselineBytes, MAX_FORWARD_BYTES, baselineBytes,
                    PlaybackAutoContext.MemoryPressure.UNKNOWN,
                    false, false, -1, false, reason);
        }

        public boolean normalMemoryEvidence() {
            return pressureUsable
                    && memoryPressure == PlaybackAutoContext.MemoryPressure.NORMAL
                    && snapshotUsable;
        }
    }

    private record Capacity(
            long targetBytes,
            PlaybackAutoContext.MemoryPressure pressure,
            boolean pressureUsable,
            boolean snapshotUsable,
            long memorySampleAtElapsedMs,
            boolean critical,
            Reason reason) {
    }

    private record ResourceLimit(long targetBytes, Reason reason) {
    }
}
