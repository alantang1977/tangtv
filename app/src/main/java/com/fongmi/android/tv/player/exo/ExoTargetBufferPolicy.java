package com.fongmi.android.tv.player.exo;

import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.PlaybackRoute;

import java.util.Objects;

/** Pure policy for sizing EXO's automatic target buffer from media demand and current memory. */
final class ExoTargetBufferPolicy {

    static final int MIB = 1024 * 1024;
    static final int MIN_TARGET_BYTES = 16 * MIB;
    static final int UNKNOWN_PROXY_VOD_TARGET_BYTES = 96 * MIB;
    static final int GUARD_TARGET_BYTES = 192 * MIB;
    static final long TARGET_BUFFER_DURATION_MS = 30_000L;
    static final long BURST_WINDOW_MS = 10_000L;
    static final int AVERAGE_HEADROOM_PERCENT = 115;
    private static final int[] TARGET_TIERS_BYTES = {
            16 * MIB,
            24 * MIB,
            48 * MIB,
            64 * MIB,
            96 * MIB,
            128 * MIB,
            192 * MIB
    };

    private ExoTargetBufferPolicy() {
    }

    static Decision resolve(
            MediaDemand mediaDemand,
            int configuredTargetBytes,
            ExoBufferBudget.Budget fallbackBudget,
            PlaybackAutoContext.DeviceFacts deviceFacts,
            long elapsedRealtimeMs) {
        return resolve(
                mediaDemand,
                configuredTargetBytes,
                fallbackBudget,
                UnknownMediaFallback.MINIMUM,
                deviceFacts,
                elapsedRealtimeMs);
    }

    static Decision resolve(
            MediaDemand mediaDemand,
            int configuredTargetBytes,
            ExoBufferBudget.Budget fallbackBudget,
            UnknownMediaFallback unknownMediaFallback,
            PlaybackAutoContext.DeviceFacts deviceFacts,
            long elapsedRealtimeMs) {
        MediaDemand media = mediaDemand == null ? MediaDemand.unknown() : mediaDemand;
        ExoBufferBudget.Budget fallback = Objects.requireNonNull(fallbackBudget);
        UnknownMediaFallback unknownFallback = unknownMediaFallback == null
                ? UnknownMediaFallback.MINIMUM : unknownMediaFallback;
        PlaybackAutoContext.DeviceFacts device = deviceFacts == null
                ? PlaybackAutoContext.DeviceFacts.unknown() : deviceFacts;
        long now = Math.max(0, elapsedRealtimeMs);

        long averageDemandBytes = media.averageReliable()
                ? applyAverageHeadroom(bytesForDuration(media.averageBitsPerSecond(), TARGET_BUFFER_DURATION_MS)) : 0;
        long burstDemandBytes = media.burstReliable()
                ? bytesForDuration(media.burstBitsPerSecond(), BURST_WINDOW_MS) : 0;
        long payloadDemandBytes = Math.max(averageDemandBytes, burstDemandBytes);

        DeviceBudget deviceBudget = resolveDeviceBudget(fallback, device, now);
        int configuredCap = Math.max(0, configuredTargetBytes);
        long safeCapacityBytes = Math.min(GUARD_TARGET_BYTES, deviceBudget.deviceBudgetBytes());
        if (configuredCap > 0) safeCapacityBytes = Math.min(safeCapacityBytes, configuredCap);
        int safeTierBytes = tierForCapacity(safeCapacityBytes);
        int mediaTierBytes = media.knownReliable()
                ? tierForDemand(payloadDemandBytes)
                : unknownFallback.targetBytes();
        int targetBytes = Math.min(mediaTierBytes, safeTierBytes);
        LimitingFactor factor = limitingFactor(
                payloadDemandBytes,
                media.knownReliable(),
                mediaTierBytes,
                safeTierBytes,
                configuredCap,
                deviceBudget);

        return new Decision(
                targetBytes,
                mediaTierBytes,
                safeTierBytes,
                payloadDemandBytes,
                averageDemandBytes,
                burstDemandBytes,
                deviceBudget.deviceBudgetBytes(),
                deviceBudget.heapBudgetBytes(),
                deviceBudget.javaHeadroomBudgetBytes(),
                deviceBudget.systemBudgetBytes(),
                configuredCap,
                deviceBudget.reserveBytes(),
                deviceBudget.lowRamDevice(),
                deviceBudget.snapshotUsable(),
                deviceBudget.pressureUsable(),
                deviceBudget.memoryPressure(),
                factor,
                unknownFallback,
                media);
    }

    static UnknownMediaFallback unknownMediaFallback(
            PlaybackAutoContext.ResourceFacts resourceFacts,
            PlaybackAutoContext.PathFacts pathFacts,
            boolean hasVideo,
            boolean adaptiveVideo,
            long elapsedRealtimeMs) {
        if (!hasVideo || adaptiveVideo) return UnknownMediaFallback.MINIMUM;
        PlaybackAutoContext.ResourceFacts resource = resourceFacts == null
                ? PlaybackAutoContext.ResourceFacts.unknown() : resourceFacts;
        PlaybackAutoContext.PathFacts path = pathFacts == null
                ? PlaybackAutoContext.PathFacts.unknown() : pathFacts;
        long now = Math.max(0, elapsedRealtimeMs);
        PlaybackAutoContext.StreamKind stream = usableValue(
                resource.streamKind(), PlaybackAutoContext.StreamKind.UNKNOWN, now);
        PlaybackAutoContext.Protocol protocol = usableValue(
                resource.protocol(), PlaybackAutoContext.Protocol.UNKNOWN, now);
        PlaybackAutoContext.TransferUnit transferUnit = usableValue(
                resource.transferUnit(), PlaybackAutoContext.TransferUnit.UNKNOWN, now);
        PlaybackAutoContext.PathKind playerPath = usableValue(
                path.playerPath(), PlaybackAutoContext.PathKind.UNKNOWN, now);
        PlaybackAutoContext.UpstreamState upstreamState = usableValue(
                path.upstreamState(), PlaybackAutoContext.UpstreamState.UNKNOWN, now);
        PlaybackRoute.Owner owner = usableValue(
                path.owner(), PlaybackRoute.Owner.UNKNOWN, now);
        if (stream != PlaybackAutoContext.StreamKind.VOD
                || playerPath != PlaybackAutoContext.PathKind.APP_INTERNAL_SERVICE
                || upstreamState != PlaybackAutoContext.UpstreamState.VISIBLE
                || owner != PlaybackRoute.Owner.APP_MAIN_SERVER
                || segmentedOrRealtime(protocol)
                || segmentedTransfer(transferUnit)
                || segmentedManifest(resource.manifest(), now)
                || hasMultipleManifestVariants(resource.manifest(), now)) {
            return UnknownMediaFallback.MINIMUM;
        }
        return UnknownMediaFallback.APP_PROXY_VOD;
    }

    static int tierForDemand(long demandBytes) {
        long demand = Math.max(0, demandBytes);
        for (int tier : TARGET_TIERS_BYTES) {
            if (demand <= tier) return tier;
        }
        return GUARD_TARGET_BYTES;
    }

    static int tierForCapacity(long capacityBytes) {
        long capacity = Math.max(0, capacityBytes);
        int selected = MIN_TARGET_BYTES;
        for (int tier : TARGET_TIERS_BYTES) {
            if (tier > capacity) break;
            selected = tier;
        }
        return selected;
    }

    static int previousTierBytes(int targetBytes) {
        int normalized = tierForCapacity(Math.max(MIN_TARGET_BYTES, targetBytes));
        int previous = MIN_TARGET_BYTES;
        for (int tier : TARGET_TIERS_BYTES) {
            if (tier >= normalized) return previous;
            previous = tier;
        }
        return previous;
    }

    static int nextTierBytes(int currentBytes, int ceilingBytes) {
        int current = tierForCapacity(Math.max(MIN_TARGET_BYTES, currentBytes));
        int ceiling = tierForCapacity(Math.max(MIN_TARGET_BYTES, ceilingBytes));
        if (current >= ceiling) return ceiling;
        for (int tier : TARGET_TIERS_BYTES) {
            if (tier > current) return Math.min(tier, ceiling);
        }
        return ceiling;
    }

    static long bytesForDuration(long bitrateBitsPerSecond, long durationMs) {
        if (bitrateBitsPerSecond <= 0 || durationMs <= 0) return 0;
        return saturatingMultiplyDivide(bitrateBitsPerSecond, durationMs, 8_000L);
    }

    private static long applyAverageHeadroom(long bytes) {
        return saturatingMultiplyDivide(bytes, AVERAGE_HEADROOM_PERCENT, 100L);
    }

    private static DeviceBudget resolveDeviceBudget(
            ExoBufferBudget.Budget fallback,
            PlaybackAutoContext.DeviceFacts device,
            long now) {
        long heapBudgetBytes = Math.max(0, fallback.heapBudgetBytes());
        long javaHeadroomBudgetBytes = -1;
        long systemBudgetBytes = -1;
        boolean lowRamDevice = fallback.lowRamDevice();
        int reserveBytes = fallback.reservedHeadroomBytes();
        boolean snapshotUsable = device.memorySnapshot().isUsable(now);
        boolean pressureUsable = device.memoryPressure().isUsable(now);
        PlaybackAutoContext.MemoryPressure pressure = pressureUsable
                ? device.memoryPressure().value() : PlaybackAutoContext.MemoryPressure.UNKNOWN;
        boolean critical = pressure == PlaybackAutoContext.MemoryPressure.CRITICAL;

        if (snapshotUsable) {
            PlaybackAutoContext.MemorySnapshot snapshot = device.memorySnapshot().value();
            if (snapshot.lowRamDevice() != null) lowRamDevice = snapshot.lowRamDevice();
            reserveBytes = lowRamDevice ? 48 * MIB : 64 * MIB;
            if (snapshot.javaHeapLimitBytes() != null) {
                ExoBufferBudget.Budget currentHeap = ExoBufferBudget.calculate(
                        ExoBufferBudget.MAX_TARGET_BYTES,
                        snapshot.javaHeapLimitBytes(),
                        lowRamDevice);
                heapBudgetBytes = Math.min(heapBudgetBytes, Math.max(0, currentHeap.heapBudgetBytes()));
            }
            if (snapshot.javaHeapHeadroomBytes() != null) {
                javaHeadroomBudgetBytes = Math.max(0, snapshot.javaHeapHeadroomBytes() - reserveBytes);
            }
            if (snapshot.systemAvailableBytes() != null && snapshot.systemThresholdBytes() != null) {
                systemBudgetBytes = Math.max(0, snapshot.systemAvailableBytes() - snapshot.systemThresholdBytes());
                if (snapshot.systemAvailableBytes() <= snapshot.systemThresholdBytes()) critical = true;
            }
            if (Boolean.TRUE.equals(snapshot.systemLowMemory())) critical = true;
        }

        long deviceBudgetBytes = heapBudgetBytes;
        if (javaHeadroomBudgetBytes >= 0) deviceBudgetBytes = Math.min(deviceBudgetBytes, javaHeadroomBudgetBytes);
        if (systemBudgetBytes >= 0) deviceBudgetBytes = Math.min(deviceBudgetBytes, systemBudgetBytes);
        if (critical) deviceBudgetBytes = Math.min(deviceBudgetBytes, MIN_TARGET_BYTES);
        return new DeviceBudget(
                Math.max(0, deviceBudgetBytes),
                heapBudgetBytes,
                javaHeadroomBudgetBytes,
                systemBudgetBytes,
                reserveBytes,
                lowRamDevice,
                snapshotUsable,
                pressureUsable,
                pressure,
                critical);
    }

    private static LimitingFactor limitingFactor(
            long payloadDemandBytes,
            boolean reliableMedia,
            int mediaTierBytes,
            int safeTierBytes,
            int configuredCapBytes,
            DeviceBudget deviceBudget) {
        if (deviceBudget.critical()) return LimitingFactor.MEMORY_PRESSURE;
        if (!reliableMedia) return LimitingFactor.UNKNOWN_MEDIA;
        if (payloadDemandBytes > GUARD_TARGET_BYTES) return LimitingFactor.GUARD;
        if (safeTierBytes >= mediaTierBytes) return LimitingFactor.MEDIA_DEMAND;
        if (configuredCapBytes > 0
                && configuredCapBytes <= GUARD_TARGET_BYTES
                && configuredCapBytes <= deviceBudget.deviceBudgetBytes()) {
            return LimitingFactor.USER_LIMIT;
        }
        if (deviceBudget.deviceBudgetBytes() <= GUARD_TARGET_BYTES) return LimitingFactor.MEMORY_BUDGET;
        return LimitingFactor.GUARD;
    }

    private static long saturatingMultiplyDivide(long value, long multiplier, long divisor) {
        if (value <= 0 || multiplier <= 0 || divisor <= 0) return 0;
        long whole = value / divisor;
        long remainder = value % divisor;
        long first = saturatingMultiply(whole, multiplier);
        long second = saturatingMultiply(remainder, multiplier) / divisor;
        return saturatingAdd(first, second);
    }

    private static long saturatingMultiply(long first, long second) {
        if (first <= 0 || second <= 0) return 0;
        return first > Long.MAX_VALUE / second ? Long.MAX_VALUE : first * second;
    }

    private static long saturatingAdd(long first, long second) {
        if (first >= Long.MAX_VALUE - second) return Long.MAX_VALUE;
        return first + second;
    }

    private static boolean segmentedOrRealtime(PlaybackAutoContext.Protocol protocol) {
        return protocol == PlaybackAutoContext.Protocol.LOCAL
                || protocol == PlaybackAutoContext.Protocol.HLS
                || protocol == PlaybackAutoContext.Protocol.DASH
                || protocol == PlaybackAutoContext.Protocol.RTSP
                || protocol == PlaybackAutoContext.Protocol.RTMP;
    }

    private static boolean segmentedTransfer(PlaybackAutoContext.TransferUnit transferUnit) {
        return transferUnit == PlaybackAutoContext.TransferUnit.SEGMENT
                || transferUnit == PlaybackAutoContext.TransferUnit.PART;
    }

    private static boolean segmentedManifest(
            PlaybackAutoContext.Fact<PlaybackAutoContext.ManifestFacts> fact,
            long now) {
        if (fact == null || !fact.isUsable(now)) return false;
        PlaybackAutoContext.ManifestKind kind = fact.value().kind();
        return kind == PlaybackAutoContext.ManifestKind.HLS_MASTER
                || kind == PlaybackAutoContext.ManifestKind.HLS_MEDIA
                || kind == PlaybackAutoContext.ManifestKind.DASH_STATIC
                || kind == PlaybackAutoContext.ManifestKind.DASH_DYNAMIC;
    }

    private static boolean hasMultipleManifestVariants(
            PlaybackAutoContext.Fact<PlaybackAutoContext.ManifestFacts> fact,
            long now) {
        if (fact == null || !fact.isUsable(now)) return false;
        Integer variants = fact.value().variantCount();
        return variants != null && variants > 1;
    }

    private static <T> T usableValue(
            PlaybackAutoContext.Fact<T> fact,
            T fallback,
            long now) {
        return fact != null && fact.isUsable(now) ? fact.value() : fallback;
    }

    enum DemandSource {
        CONTENT_LENGTH("content-length", PlaybackAutoContext.ValueSource.ESTIMATOR),
        FORMAT("format", PlaybackAutoContext.ValueSource.ESTIMATOR),
        OBSERVED_LOAD("observed-load", PlaybackAutoContext.ValueSource.ESTIMATOR),
        BYTE_SLOPE("byte-slope", PlaybackAutoContext.ValueSource.ESTIMATOR),
        OBSERVED("observed", PlaybackAutoContext.ValueSource.ESTIMATOR),
        HYBRID("hybrid", PlaybackAutoContext.ValueSource.ESTIMATOR),
        SELECTED_TRACK("selected-track", PlaybackAutoContext.ValueSource.PLAYER_CALLBACK),
        UNKNOWN("unknown", PlaybackAutoContext.ValueSource.UNKNOWN);

        private final String label;
        private final PlaybackAutoContext.ValueSource telemetrySource;

        DemandSource(String label, PlaybackAutoContext.ValueSource telemetrySource) {
            this.label = label;
            this.telemetrySource = telemetrySource;
        }

        String label() {
            return label;
        }

        PlaybackAutoContext.ValueSource telemetrySource() {
            return telemetrySource;
        }
    }

    enum LimitingFactor {
        MEDIA_DEMAND("media-demand"),
        USER_LIMIT("user-limit"),
        MEMORY_BUDGET("memory-budget"),
        MEMORY_PRESSURE("memory-pressure"),
        GUARD("guard"),
        UNKNOWN_MEDIA("unknown-media");

        private final String label;

        LimitingFactor(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    enum UnknownMediaFallback {
        MINIMUM("minimum", MIN_TARGET_BYTES),
        APP_PROXY_VOD("app-proxy-vod", UNKNOWN_PROXY_VOD_TARGET_BYTES);

        private final String label;
        private final int targetBytes;

        UnknownMediaFallback(String label, int targetBytes) {
            this.label = label;
            this.targetBytes = targetBytes;
        }

        String label() {
            return label;
        }

        int targetBytes() {
            return targetBytes;
        }
    }

    record MediaDemand(
            long averageBitsPerSecond,
            DemandSource averageSource,
            PlaybackAutoContext.Confidence averageConfidence,
            long burstBitsPerSecond,
            DemandSource burstSource,
            PlaybackAutoContext.Confidence burstConfidence) {

        MediaDemand {
            averageBitsPerSecond = Math.max(0, averageBitsPerSecond);
            burstBitsPerSecond = Math.max(0, burstBitsPerSecond);
            averageSource = averageSource == null ? DemandSource.UNKNOWN : averageSource;
            burstSource = burstSource == null ? DemandSource.UNKNOWN : burstSource;
            averageConfidence = averageConfidence == null
                    ? PlaybackAutoContext.Confidence.UNKNOWN : averageConfidence;
            burstConfidence = burstConfidence == null
                    ? PlaybackAutoContext.Confidence.UNKNOWN : burstConfidence;
        }

        static MediaDemand unknown() {
            return new MediaDemand(
                    0,
                    DemandSource.UNKNOWN,
                    PlaybackAutoContext.Confidence.UNKNOWN,
                    0,
                    DemandSource.UNKNOWN,
                    PlaybackAutoContext.Confidence.UNKNOWN);
        }

        boolean averageReliable() {
            return reliable(averageBitsPerSecond, averageSource, averageConfidence);
        }

        boolean burstReliable() {
            return reliable(burstBitsPerSecond, burstSource, burstConfidence);
        }

        boolean knownReliable() {
            return averageReliable() || burstReliable();
        }

        private static boolean reliable(
                long bitrate,
                DemandSource source,
                PlaybackAutoContext.Confidence confidence) {
            return bitrate > 0
                    && source != DemandSource.UNKNOWN
                    && confidence != PlaybackAutoContext.Confidence.LOW
                    && confidence != PlaybackAutoContext.Confidence.UNKNOWN;
        }
    }

    record Decision(
            int targetBytes,
            int mediaTierBytes,
            int safeTierBytes,
            long payloadDemandBytes,
            long averageDemandBytes,
            long burstDemandBytes,
            long deviceBudgetBytes,
            long heapBudgetBytes,
            long javaHeadroomBudgetBytes,
            long systemBudgetBytes,
            int configuredCapBytes,
            int reserveBytes,
            boolean lowRamDevice,
            boolean memorySnapshotUsable,
            boolean memoryPressureUsable,
            PlaybackAutoContext.MemoryPressure memoryPressure,
            LimitingFactor limitingFactor,
            UnknownMediaFallback unknownMediaFallback,
            MediaDemand mediaDemand) {

        Decision {
            memoryPressure = memoryPressure == null
                    ? PlaybackAutoContext.MemoryPressure.UNKNOWN : memoryPressure;
            limitingFactor = limitingFactor == null ? LimitingFactor.UNKNOWN_MEDIA : limitingFactor;
            unknownMediaFallback = unknownMediaFallback == null
                    ? UnknownMediaFallback.MINIMUM : unknownMediaFallback;
            mediaDemand = mediaDemand == null ? MediaDemand.unknown() : mediaDemand;
        }
    }

    private record DeviceBudget(
            long deviceBudgetBytes,
            long heapBudgetBytes,
            long javaHeadroomBudgetBytes,
            long systemBudgetBytes,
            int reserveBytes,
            boolean lowRamDevice,
            boolean snapshotUsable,
            boolean pressureUsable,
            PlaybackAutoContext.MemoryPressure memoryPressure,
            boolean critical) {
    }
}
