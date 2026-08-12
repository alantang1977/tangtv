package com.fongmi.android.tv.player.mpv;

import com.fongmi.android.tv.player.PlaybackAutoContext;

/** Conservative, side-effect-free initial cache policy for MPV automatic mode. */
public final class MpvAutoControlPolicy {

    private static final long MEBIBYTE = 1024L * 1024L;
    private static final long SMALL_HEAP_LIMIT_BYTES = 192L * MEBIBYTE;
    private static final long LOW_JAVA_HEADROOM_BYTES = 64L * MEBIBYTE;
    private static final long HIGH_HEAP_LIMIT_BYTES = 512L * MEBIBYTE;
    private static final long HIGH_JAVA_HEADROOM_BYTES = 256L * MEBIBYTE;
    private static final long HIGH_SYSTEM_SURPLUS_BYTES = 768L * MEBIBYTE;

    public static final long MIN_FORWARD_BYTES = 24L * MEBIBYTE;
    public static final long CONSERVATIVE_FORWARD_BYTES = 48L * MEBIBYTE;
    public static final long BALANCED_FORWARD_BYTES = 64L * MEBIBYTE;
    public static final long INITIAL_BACK_BYTES = 0L;

    private MpvAutoControlPolicy() {
    }

    public static Request requestFrom(
            PlaybackAutoContext context,
            boolean automatic,
            boolean mpv,
            boolean performancePriority,
            long nowMs) {
        PlaybackAutoContext current = context == null ? PlaybackAutoContext.empty() : context;
        long now = Math.max(0, nowMs);
        PlaybackAutoContext.Fact<PlaybackAutoContext.MemoryPressure> pressureFact =
                current.device().memoryPressure();
        PlaybackAutoContext.Fact<PlaybackAutoContext.MemorySnapshot> snapshotFact =
                current.device().memorySnapshot();
        PlaybackAutoContext.Fact<PlaybackAutoContext.Protocol> protocolFact =
                current.resource().protocol();
        PlaybackAutoContext.Fact<PlaybackAutoContext.StreamKind> streamFact =
                current.resource().streamKind();
        PlaybackAutoContext.Fact<PlaybackAutoContext.PathKind> playerPathFact =
                current.path().playerPath();
        PlaybackAutoContext.Fact<PlaybackAutoContext.PathKind> upstreamPathFact =
                current.path().upstreamPath();
        PlaybackAutoContext.Fact<PlaybackAutoContext.UpstreamState> upstreamStateFact =
                current.path().upstreamState();
        boolean pressureUsable = pressureFact.isUsable(now);
        boolean snapshotUsable = snapshotFact.isUsable(now)
                && snapshotFact.value().hasEvidence();
        boolean protocolUsable = protocolFact.isUsable(now);
        boolean streamUsable = streamFact.isUsable(now);
        boolean playerPathUsable = playerPathFact.isUsable(now);
        boolean upstreamPathUsable = upstreamPathFact.isUsable(now);
        boolean upstreamStateUsable = upstreamStateFact.isUsable(now);
        return new Request(
                automatic,
                mpv,
                performancePriority,
                pressureUsable,
                pressureUsable ? pressureFact.value() : PlaybackAutoContext.MemoryPressure.UNKNOWN,
                snapshotUsable,
                snapshotUsable ? snapshotFact.value() : PlaybackAutoContext.MemorySnapshot.unknown(),
                protocolUsable,
                protocolUsable ? protocolFact.value() : PlaybackAutoContext.Protocol.UNKNOWN,
                streamUsable,
                streamUsable ? streamFact.value() : PlaybackAutoContext.StreamKind.UNKNOWN,
                playerPathUsable,
                playerPathUsable ? playerPathFact.value() : PlaybackAutoContext.PathKind.UNKNOWN,
                upstreamPathUsable,
                upstreamPathUsable ? upstreamPathFact.value() : PlaybackAutoContext.PathKind.UNKNOWN,
                upstreamStateUsable,
                upstreamStateUsable ? upstreamStateFact.value() : PlaybackAutoContext.UpstreamState.UNKNOWN);
    }

    public static Decision resolve(Request request) {
        Request input = request == null ? Request.inactive() : request;
        if (!input.automatic() || !input.mpv()) {
            return Decision.hold(Reason.NOT_AUTOMATIC_MPV);
        }
        if (!input.performancePriority()) {
            return Decision.hold(Reason.CONFIG_PRIORITY);
        }

        PlaybackAutoContext.MemorySnapshot memory = input.memorySnapshot();
        if (input.pressureUsable()
                && input.memoryPressure() == PlaybackAutoContext.MemoryPressure.CRITICAL) {
            return apply(MIN_FORWARD_BYTES, Reason.CRITICAL_PRESSURE, false);
        }
        if (input.snapshotUsable() && isSystemLow(memory)) {
            return apply(MIN_FORWARD_BYTES, Reason.SYSTEM_LOW_MEMORY, false);
        }
        if (input.snapshotUsable() && Boolean.TRUE.equals(memory.lowRamDevice())) {
            return apply(MIN_FORWARD_BYTES, Reason.LOW_RAM, false);
        }
        if (input.snapshotUsable()
                && memory.javaHeapLimitBytes() != null
                && memory.javaHeapLimitBytes() <= SMALL_HEAP_LIMIT_BYTES) {
            return apply(MIN_FORWARD_BYTES, Reason.SMALL_HEAP, false);
        }
        if (input.snapshotUsable()
                && memory.javaHeapHeadroomBytes() != null
                && memory.javaHeapHeadroomBytes() <= LOW_JAVA_HEADROOM_BYTES) {
            return apply(MIN_FORWARD_BYTES, Reason.LOW_JAVA_HEADROOM, false);
        }
        if (input.pressureUsable()
                && input.memoryPressure() == PlaybackAutoContext.MemoryPressure.MODERATE) {
            return apply(MIN_FORWARD_BYTES, Reason.MODERATE_PRESSURE, false);
        }
        if (!input.snapshotUsable()) {
            return apply(MIN_FORWARD_BYTES, Reason.MEMORY_UNKNOWN, false);
        }

        long forwardBytes = CONSERVATIVE_FORWARD_BYTES;
        Reason reason = Reason.NORMAL_MEMORY;
        if (input.pressureUsable()
                && input.memoryPressure() == PlaybackAutoContext.MemoryPressure.NORMAL
                && input.snapshotUsable()
                && hasHighHeadroom(memory)) {
            forwardBytes = BALANCED_FORWARD_BYTES;
            reason = Reason.HIGH_HEADROOM;
        }

        if (isLocal(input) && forwardBytes > MIN_FORWARD_BYTES) {
            forwardBytes = MIN_FORWARD_BYTES;
            reason = Reason.LOCAL_RESOURCE_CAP;
            return apply(forwardBytes, reason, true);
        }
        if (isLive(input) && forwardBytes > CONSERVATIVE_FORWARD_BYTES) {
            forwardBytes = CONSERVATIVE_FORWARD_BYTES;
            reason = Reason.LIVE_RESOURCE_CAP;
            return apply(forwardBytes, reason, true);
        }
        if (isOpaque(input) && forwardBytes > CONSERVATIVE_FORWARD_BYTES) {
            forwardBytes = CONSERVATIVE_FORWARD_BYTES;
            reason = Reason.OPAQUE_PATH_CAP;
            return apply(forwardBytes, reason, true);
        }
        return apply(forwardBytes, reason, false);
    }

    private static Decision apply(long forwardBytes, Reason reason, boolean capped) {
        return new Decision(Action.APPLY_BASELINE, reason, forwardBytes, INITIAL_BACK_BYTES, capped);
    }

    private static boolean isSystemLow(PlaybackAutoContext.MemorySnapshot memory) {
        if (Boolean.TRUE.equals(memory.systemLowMemory())) return true;
        Long available = memory.systemAvailableBytes();
        Long threshold = memory.systemThresholdBytes();
        return available != null && threshold != null && available <= threshold;
    }

    private static boolean hasHighHeadroom(PlaybackAutoContext.MemorySnapshot memory) {
        if (!Boolean.FALSE.equals(memory.lowRamDevice())) return false;
        if (Boolean.TRUE.equals(memory.systemLowMemory())) return false;
        Long heapLimit = memory.javaHeapLimitBytes();
        Long heapHeadroom = memory.javaHeapHeadroomBytes();
        Long available = memory.systemAvailableBytes();
        Long threshold = memory.systemThresholdBytes();
        if (heapLimit == null || heapHeadroom == null || available == null || threshold == null) {
            return false;
        }
        long systemSurplus = available <= threshold ? 0 : available - threshold;
        return heapLimit >= HIGH_HEAP_LIMIT_BYTES
                && heapHeadroom >= HIGH_JAVA_HEADROOM_BYTES
                && systemSurplus >= HIGH_SYSTEM_SURPLUS_BYTES;
    }

    private static boolean isLocal(Request input) {
        return input.protocolUsable() && input.protocol() == PlaybackAutoContext.Protocol.LOCAL
                || input.playerPathUsable() && input.playerPath() == PlaybackAutoContext.PathKind.LOCAL
                || input.upstreamPathUsable() && input.upstreamPath() == PlaybackAutoContext.PathKind.LOCAL;
    }

    private static boolean isLive(Request input) {
        return input.streamKindUsable()
                && (input.streamKind() == PlaybackAutoContext.StreamKind.LIVE
                || input.streamKind() == PlaybackAutoContext.StreamKind.LOW_LATENCY_LIVE)
                || input.protocolUsable()
                && (input.protocol() == PlaybackAutoContext.Protocol.RTSP
                || input.protocol() == PlaybackAutoContext.Protocol.RTMP);
    }

    private static boolean isOpaque(Request input) {
        return input.playerPathUsable()
                && input.playerPath() == PlaybackAutoContext.PathKind.EXTERNAL_LOOPBACK
                || input.upstreamStateUsable()
                && input.upstreamState() == PlaybackAutoContext.UpstreamState.OPAQUE;
    }

    public enum Action {
        HOLD("hold"),
        APPLY_BASELINE("apply-baseline");

        private final String label;

        Action(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Reason {
        NOT_AUTOMATIC_MPV("not-automatic-mpv"),
        CONFIG_PRIORITY("mpv-conf-priority"),
        STALE_SESSION("stale-session"),
        CRITICAL_PRESSURE("critical-pressure"),
        SYSTEM_LOW_MEMORY("system-low-memory"),
        LOW_RAM("low-ram"),
        SMALL_HEAP("small-heap"),
        LOW_JAVA_HEADROOM("low-java-headroom"),
        MODERATE_PRESSURE("moderate-pressure"),
        MEMORY_UNKNOWN("memory-unknown"),
        NORMAL_MEMORY("normal-memory"),
        HIGH_HEADROOM("high-headroom"),
        LOCAL_RESOURCE_CAP("local-resource-cap"),
        LIVE_RESOURCE_CAP("live-resource-cap"),
        OPAQUE_PATH_CAP("opaque-path-cap");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Request(
            boolean automatic,
            boolean mpv,
            boolean performancePriority,
            boolean pressureUsable,
            PlaybackAutoContext.MemoryPressure memoryPressure,
            boolean snapshotUsable,
            PlaybackAutoContext.MemorySnapshot memorySnapshot,
            boolean protocolUsable,
            PlaybackAutoContext.Protocol protocol,
            boolean streamKindUsable,
            PlaybackAutoContext.StreamKind streamKind,
            boolean playerPathUsable,
            PlaybackAutoContext.PathKind playerPath,
            boolean upstreamPathUsable,
            PlaybackAutoContext.PathKind upstreamPath,
            boolean upstreamStateUsable,
            PlaybackAutoContext.UpstreamState upstreamState) {

        public Request {
            memoryPressure = memoryPressure == null
                    ? PlaybackAutoContext.MemoryPressure.UNKNOWN : memoryPressure;
            memorySnapshot = memorySnapshot == null
                    ? PlaybackAutoContext.MemorySnapshot.unknown() : memorySnapshot;
            protocol = protocol == null ? PlaybackAutoContext.Protocol.UNKNOWN : protocol;
            streamKind = streamKind == null ? PlaybackAutoContext.StreamKind.UNKNOWN : streamKind;
            playerPath = playerPath == null ? PlaybackAutoContext.PathKind.UNKNOWN : playerPath;
            upstreamPath = upstreamPath == null ? PlaybackAutoContext.PathKind.UNKNOWN : upstreamPath;
            upstreamState = upstreamState == null
                    ? PlaybackAutoContext.UpstreamState.UNKNOWN : upstreamState;
        }

        static Request inactive() {
            return new Request(false, false, false, false,
                    PlaybackAutoContext.MemoryPressure.UNKNOWN, false,
                    PlaybackAutoContext.MemorySnapshot.unknown(), false,
                    PlaybackAutoContext.Protocol.UNKNOWN, false,
                    PlaybackAutoContext.StreamKind.UNKNOWN, false,
                    PlaybackAutoContext.PathKind.UNKNOWN, false,
                    PlaybackAutoContext.PathKind.UNKNOWN, false,
                    PlaybackAutoContext.UpstreamState.UNKNOWN);
        }
    }

    public record Decision(
            Action action,
            Reason reason,
            long forwardBytes,
            long backBytes,
            boolean capped) {

        public Decision {
            action = action == null ? Action.HOLD : action;
            reason = reason == null ? Reason.MEMORY_UNKNOWN : reason;
            forwardBytes = action == Action.APPLY_BASELINE ? Math.max(0, forwardBytes) : -1;
            backBytes = action == Action.APPLY_BASELINE ? Math.max(0, backBytes) : -1;
        }

        public static Decision hold(Reason reason) {
            return new Decision(Action.HOLD, reason, -1, -1, false);
        }

        public boolean requestsApply() {
            return action == Action.APPLY_BASELINE;
        }

        public String targetLabel() {
            if (!requestsApply()) return "unchanged";
            return "forward-" + forwardBytes + "-back-" + backBytes;
        }
    }
}
