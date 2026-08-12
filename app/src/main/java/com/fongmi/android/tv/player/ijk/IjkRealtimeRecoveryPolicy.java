package com.fongmi.android.tv.player.ijk;

import com.fongmi.android.tv.player.PlaybackAutoContext;

/** Pure decision policy for recovering an IJK RTSP/RTMP session with stale queued data. */
public final class IjkRealtimeRecoveryPolicy {

    public static final long ABSOLUTE_BACKLOG_FLOOR_MS = 12_000L;
    public static final long URGENT_BACKLOG_FLOOR_MS = 30_000L;
    public static final long GROWING_BACKLOG_FLOOR_MS = 4_000L;
    public static final long MIN_DURATION_GROWTH_MS_PER_SECOND = 500L;
    public static final long MIN_BYTES_GROWTH_PER_SECOND = 32L * 1024L;
    public static final long MIN_PACKETS_GROWTH_PER_SECOND = 4L;
    public static final int CAPACITY_PRESSURE_PERMILLE = 850;
    public static final int CONFIRMATION_SAMPLES = 3;
    public static final int URGENT_CONFIRMATION_SAMPLES = 2;
    public static final long CONFIRMATION_WINDOW_MS = 10_000L;
    public static final long URGENT_CONFIRMATION_WINDOW_MS = 5_000L;

    private IjkRealtimeRecoveryPolicy() {
    }

    public static Decision resolve(Request request) {
        Request input = request == null ? Request.inactive() : request;
        Thresholds thresholds = thresholds(input.appliedConfig());
        Trigger trigger = classifyRisk(
                input.queue(),
                input.durationGrowthMsPerSecond(),
                input.bytesGrowthPerSecond(),
                input.packetsGrowthPerSecond(),
                thresholds);
        if (!input.automatic() || !input.ijk()) {
            return hold(input, trigger, thresholds, Reason.NOT_AUTOMATIC_IJK);
        }
        if (!input.protocolUsable() || !isRealtime(input.protocol())) {
            return hold(input, trigger, thresholds, Reason.NOT_REALTIME_PROTOCOL);
        }
        if (!input.active()) {
            return hold(input, trigger, thresholds, Reason.INACTIVE);
        }
        if (!input.startupComplete()) {
            return hold(input, trigger, thresholds, Reason.STARTUP);
        }
        if (!input.normalSpeed()) {
            return hold(input, trigger, thresholds, Reason.NON_UNIT_SPEED);
        }
        if (input.userSeeking()) {
            return hold(input, trigger, thresholds, Reason.USER_SEEK);
        }
        if (input.actionPending()) {
            return hold(input, trigger, thresholds, Reason.ACTION_PENDING);
        }
        if (!input.queue().durationUsable()) {
            return hold(input, trigger, thresholds, Reason.EVIDENCE_UNKNOWN);
        }
        if (trigger == Trigger.NONE) {
            return hold(input, trigger, thresholds, Reason.HEALTHY);
        }

        boolean urgent = input.queue().playableDurationMs()
                >= thresholds.urgentBacklogMs();
        int requiredSamples = urgent
                ? URGENT_CONFIRMATION_SAMPLES : CONFIRMATION_SAMPLES;
        long requiredWindowMs = urgent
                ? URGENT_CONFIRMATION_WINDOW_MS : CONFIRMATION_WINDOW_MS;
        boolean confirmed = input.consecutiveRiskSamples() >= requiredSamples
                && input.riskSinceMs() >= 0
                && input.nowMs() - input.riskSinceMs() >= requiredWindowMs;
        if (!confirmed) {
            return hold(input, trigger, thresholds, Reason.CONFIRMING);
        }
        Reason reason = switch (trigger) {
            case BACKLOG_EXCEEDED -> Reason.REBUILD_BACKLOG;
            case BACKLOG_GROWING -> Reason.REBUILD_GROWTH;
            case CAPACITY_PRESSURE -> Reason.REBUILD_CAPACITY;
            case NONE -> Reason.HEALTHY;
        };
        return new Decision(
                Action.REBUILD_SESSION,
                trigger,
                reason,
                true,
                thresholds,
                input.queue(),
                input.durationGrowthMsPerSecond(),
                input.bytesGrowthPerSecond(),
                input.packetsGrowthPerSecond());
    }

    public static Trigger classifyRisk(
            QueueSnapshot queue,
            long durationGrowthMsPerSecond,
            long bytesGrowthPerSecond,
            long packetsGrowthPerSecond,
            Thresholds thresholds) {
        QueueSnapshot current = queue == null ? QueueSnapshot.unknown() : queue;
        Thresholds safe = thresholds == null
                ? thresholds(IjkBufferPolicy.safeInitialConfig()) : thresholds;
        if (!current.durationUsable()) return Trigger.NONE;
        if (current.playableDurationMs() >= safe.absoluteBacklogMs()) {
            return Trigger.BACKLOG_EXCEEDED;
        }
        if (current.playableDurationMs() >= safe.growingBacklogMs()
                && durationGrowthMsPerSecond
                >= MIN_DURATION_GROWTH_MS_PER_SECOND) {
            return Trigger.BACKLOG_GROWING;
        }
        boolean queueGrowing = bytesGrowthPerSecond
                >= MIN_BYTES_GROWTH_PER_SECOND
                || packetsGrowthPerSecond
                >= MIN_PACKETS_GROWTH_PER_SECOND;
        if (current.playableDurationMs() >= safe.growingBacklogMs()
                && current.occupancyPermille(safe.maxBufferBytes())
                >= CAPACITY_PRESSURE_PERMILLE
                && queueGrowing) {
            return Trigger.CAPACITY_PRESSURE;
        }
        return Trigger.NONE;
    }

    public static Thresholds thresholds(IjkBufferPolicy.Config config) {
        IjkBufferPolicy.Config safe = config == null
                ? IjkBufferPolicy.safeInitialConfig() : config;
        long lastWaterMs = Math.max(1, safe.lastWaterMs());
        long growing = Math.max(GROWING_BACKLOG_FLOOR_MS,
                multiplySaturated(lastWaterMs, 4));
        long absolute = Math.max(ABSOLUTE_BACKLOG_FLOOR_MS,
                multiplySaturated(lastWaterMs, 8));
        long urgent = Math.max(URGENT_BACKLOG_FLOOR_MS,
                multiplySaturated(absolute, 2));
        return new Thresholds(growing, absolute, urgent,
                safe.maxBufferBytes());
    }

    private static Decision hold(
            Request input,
            Trigger trigger,
            Thresholds thresholds,
            Reason reason) {
        return new Decision(
                Action.HOLD,
                trigger,
                reason,
                false,
                thresholds,
                input.queue(),
                input.durationGrowthMsPerSecond(),
                input.bytesGrowthPerSecond(),
                input.packetsGrowthPerSecond());
    }

    private static boolean isRealtime(PlaybackAutoContext.Protocol protocol) {
        return protocol == PlaybackAutoContext.Protocol.RTSP
                || protocol == PlaybackAutoContext.Protocol.RTMP;
    }

    private static long multiplySaturated(long value, long multiplier) {
        if (value <= 0 || multiplier <= 0) return 0;
        return value > Long.MAX_VALUE / multiplier
                ? Long.MAX_VALUE : value * multiplier;
    }

    private static long addSaturated(long first, long second) {
        long safeFirst = Math.max(0, first);
        long safeSecond = Math.max(0, second);
        return safeFirst > Long.MAX_VALUE - safeSecond
                ? Long.MAX_VALUE : safeFirst + safeSecond;
    }

    public enum Action {
        HOLD("hold"),
        REBUILD_SESSION("rebuild-session");

        private final String label;

        Action(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Trigger {
        NONE("none"),
        BACKLOG_EXCEEDED("backlog-exceeded"),
        BACKLOG_GROWING("backlog-growing"),
        CAPACITY_PRESSURE("capacity-pressure");

        private final String label;

        Trigger(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Reason {
        NOT_AUTOMATIC_IJK("not-automatic-ijk"),
        NOT_REALTIME_PROTOCOL("not-realtime-protocol"),
        INACTIVE("inactive"),
        STARTUP("startup"),
        NON_UNIT_SPEED("non-unit-speed"),
        USER_SEEK("user-seek"),
        ACTION_PENDING("action-pending"),
        EVIDENCE_UNKNOWN("evidence-unknown"),
        HEALTHY("healthy"),
        CONFIRMING("confirming"),
        REBUILD_BACKLOG("rebuild-backlog"),
        REBUILD_GROWTH("rebuild-growth"),
        REBUILD_CAPACITY("rebuild-capacity"),
        STALE_SESSION("stale-session"),
        STALE_SAMPLE("stale-sample");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record QueueSnapshot(
            boolean available,
            boolean hasAudioTrack,
            boolean hasVideoTrack,
            long audioDurationMs,
            long videoDurationMs,
            long audioBytes,
            long videoBytes,
            long audioPackets,
            long videoPackets) {

        public QueueSnapshot {
            audioDurationMs = Math.max(0, audioDurationMs);
            videoDurationMs = Math.max(0, videoDurationMs);
            audioBytes = Math.max(0, audioBytes);
            videoBytes = Math.max(0, videoBytes);
            audioPackets = Math.max(0, audioPackets);
            videoPackets = Math.max(0, videoPackets);
        }

        public static QueueSnapshot unknown() {
            return new QueueSnapshot(false, false, false,
                    0, 0, 0, 0, 0, 0);
        }

        public long playableDurationMs() {
            if (!available) return -1;
            if (hasAudioTrack && hasVideoTrack) {
                return Math.min(audioDurationMs, videoDurationMs);
            }
            if (hasAudioTrack) return audioDurationMs;
            if (hasVideoTrack) return videoDurationMs;
            return audioDurationMs > 0 && videoDurationMs > 0
                    ? Math.min(audioDurationMs, videoDurationMs) : -1;
        }

        public boolean durationUsable() {
            return playableDurationMs() >= 0;
        }

        public long totalBytes() {
            return addSaturated(audioBytes, videoBytes);
        }

        public long totalPackets() {
            return addSaturated(audioPackets, videoPackets);
        }

        public int occupancyPermille(long maxBufferBytes) {
            if (maxBufferBytes <= 0) return 0;
            long bytes = totalBytes();
            if (bytes >= maxBufferBytes) return 1_000;
            return (int) Math.min(1_000,
                    Math.round(bytes / (double) maxBufferBytes * 1_000d));
        }
    }

    public record Thresholds(
            long growingBacklogMs,
            long absoluteBacklogMs,
            long urgentBacklogMs,
            long maxBufferBytes) {

        public Thresholds {
            growingBacklogMs = Math.max(1, growingBacklogMs);
            absoluteBacklogMs = Math.max(growingBacklogMs,
                    absoluteBacklogMs);
            urgentBacklogMs = Math.max(absoluteBacklogMs,
                    urgentBacklogMs);
            maxBufferBytes = Math.max(1, maxBufferBytes);
        }
    }

    public record Request(
            boolean automatic,
            boolean ijk,
            boolean protocolUsable,
            PlaybackAutoContext.Protocol protocol,
            boolean active,
            boolean startupComplete,
            boolean normalSpeed,
            boolean userSeeking,
            boolean actionPending,
            QueueSnapshot queue,
            IjkBufferPolicy.Config appliedConfig,
            long durationGrowthMsPerSecond,
            long bytesGrowthPerSecond,
            long packetsGrowthPerSecond,
            int consecutiveRiskSamples,
            long riskSinceMs,
            long nowMs) {

        public Request {
            protocol = protocol == null
                    ? PlaybackAutoContext.Protocol.UNKNOWN : protocol;
            queue = queue == null ? QueueSnapshot.unknown() : queue;
            appliedConfig = appliedConfig == null
                    ? IjkBufferPolicy.safeInitialConfig() : appliedConfig;
            consecutiveRiskSamples = Math.max(0, consecutiveRiskSamples);
            riskSinceMs = Math.max(-1, riskSinceMs);
            nowMs = Math.max(0, nowMs);
        }

        static Request inactive() {
            return new Request(false, false, false,
                    PlaybackAutoContext.Protocol.UNKNOWN, false, false,
                    false, false, false, QueueSnapshot.unknown(),
                    IjkBufferPolicy.safeInitialConfig(), Long.MIN_VALUE,
                    Long.MIN_VALUE, Long.MIN_VALUE, 0, -1, 0);
        }
    }

    public record Decision(
            Action action,
            Trigger trigger,
            Reason reason,
            boolean confirmed,
            Thresholds thresholds,
            QueueSnapshot queue,
            long durationGrowthMsPerSecond,
            long bytesGrowthPerSecond,
            long packetsGrowthPerSecond) {

        public Decision {
            action = action == null ? Action.HOLD : action;
            trigger = trigger == null ? Trigger.NONE : trigger;
            reason = reason == null ? Reason.EVIDENCE_UNKNOWN : reason;
            thresholds = thresholds == null
                    ? IjkRealtimeRecoveryPolicy.thresholds(
                    IjkBufferPolicy.safeInitialConfig()) : thresholds;
            queue = queue == null ? QueueSnapshot.unknown() : queue;
        }

        public boolean requestsRecovery() {
            return action == Action.REBUILD_SESSION;
        }
    }
}
