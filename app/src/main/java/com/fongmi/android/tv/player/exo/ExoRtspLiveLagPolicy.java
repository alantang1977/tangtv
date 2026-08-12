package com.fongmi.android.tv.player.exo;

/**
 * Conservative action policy for an EXO RTSP live session that keeps falling behind.
 *
 * <p>The policy never infers a live edge from a large buffer alone. A seek is allowed only
 * when the caller has a reliable dynamic live edge and Media3 exposes the default-position
 * command. Otherwise a confirmed risk may only rebuild the RTSP media session.</p>
 */
public final class ExoRtspLiveLagPolicy {

    public static final long MAX_LAG_MS = 30_000L;
    public static final long GROWING_LAG_MIN_MS = 15_000L;
    public static final long URGENT_LAG_MS = 120_000L;
    public static final long LAG_GROWTH_MS_PER_SECOND = 500L;
    public static final long BUFFER_GROWTH_MIN_MS = 90_000L;
    public static final long BUFFER_GROWTH_MS_PER_SECOND = 500L;
    public static final int CONFIRMATION_SAMPLES = 3;
    public static final int URGENT_CONFIRMATION_SAMPLES = 2;
    public static final long CONFIRMATION_WINDOW_MS = 10_000L;
    public static final long URGENT_CONFIRMATION_WINDOW_MS = 5_000L;
    public static final long SEEK_COOLDOWN_MS = 30_000L;
    public static final long REBUILD_COOLDOWN_MS = 90_000L;
    public static final int MAX_SEEK_ATTEMPTS = 1;
    public static final int MAX_REBUILD_ATTEMPTS = 2;
    public static final int MAX_RECOVERY_ATTEMPTS = 3;

    private ExoRtspLiveLagPolicy() {
    }

    public static Decision resolve(Request request) {
        Request input = request == null ? Request.inactive() : request;
        Trigger trigger = classifyRisk(
                input.liveLagMs(),
                input.lagGrowthMsPerSecond(),
                input.bufferedMs(),
                input.bufferGrowthMsPerSecond());
        if (!input.automatic() || !input.exo()) return hold(input, trigger, Reason.NOT_AUTOMATIC_EXO);
        if (!input.rtsp()) return hold(input, trigger, Reason.NOT_RTSP);
        if (!input.live()) return hold(input, trigger, Reason.NOT_LIVE);
        if (!input.active()) return hold(input, trigger, Reason.INACTIVE);
        if (!input.startupComplete()) return hold(input, trigger, Reason.STARTUP);
        if (input.userSeeking()) return hold(input, trigger, Reason.USER_SEEK);
        if (input.actionPending()) return hold(input, trigger, Reason.ACTION_PENDING);
        if (trigger == Trigger.NONE) {
            boolean unknown = input.liveLagMs() < 0 && input.bufferedMs() < 0;
            return hold(input, trigger, unknown ? Reason.EVIDENCE_UNKNOWN : Reason.HEALTHY);
        }

        boolean urgent = input.liveLagMs() >= URGENT_LAG_MS;
        int requiredSamples = urgent ? URGENT_CONFIRMATION_SAMPLES : CONFIRMATION_SAMPLES;
        long requiredWindowMs = urgent ? URGENT_CONFIRMATION_WINDOW_MS : CONFIRMATION_WINDOW_MS;
        boolean confirmed = input.consecutiveRiskSamples() >= requiredSamples
                && input.riskSinceMs() >= 0
                && input.nowMs() - input.riskSinceMs() >= requiredWindowMs;
        if (!confirmed) return hold(input, trigger, Reason.CONFIRMING);
        if (input.recoveryAttempts() >= MAX_RECOVERY_ATTEMPTS) {
            return hold(input, trigger, Reason.RETRY_LIMIT);
        }

        long cooldownRemainingMs = cooldownRemaining(input);
        if (cooldownRemainingMs > 0) {
            return new Decision(Action.HOLD, trigger, Reason.COOLDOWN, true,
                    cooldownRemainingMs, input.liveLagMs(), input.lagGrowthMsPerSecond(),
                    input.bufferedMs(), input.bufferGrowthMsPerSecond());
        }

        if (input.liveLagMs() >= 0
                && input.liveEdgeReliable()
                && input.seekAvailable()
                && input.seekAttempts() < MAX_SEEK_ATTEMPTS
                && input.rebuildAttempts() == 0) {
            return action(input, trigger, Action.SEEK_LIVE_EDGE, Reason.SEEK_LIVE_EDGE);
        }
        if (input.rebuildAttempts() < MAX_REBUILD_ATTEMPTS) {
            Reason reason = switch (trigger) {
                case LAG_EXCEEDED -> Reason.REBUILD_LAG;
                case LAG_GROWING -> Reason.REBUILD_LAG_GROWTH;
                case BUFFER_GROWING -> Reason.REBUILD_BUFFER_GROWTH;
                case NONE -> Reason.REBUILD_LAG;
            };
            return action(input, trigger, Action.REBUILD_SESSION, reason);
        }
        return hold(input, trigger, Reason.RETRY_LIMIT);
    }

    public static Trigger classifyRisk(long liveLagMs, long lagGrowthMsPerSecond,
                                       long bufferedMs, long bufferGrowthMsPerSecond) {
        if (liveLagMs >= MAX_LAG_MS) return Trigger.LAG_EXCEEDED;
        if (liveLagMs >= GROWING_LAG_MIN_MS
                && lagGrowthMsPerSecond >= LAG_GROWTH_MS_PER_SECOND) {
            return Trigger.LAG_GROWING;
        }
        if (liveLagMs < 0
                && bufferedMs >= BUFFER_GROWTH_MIN_MS
                && bufferGrowthMsPerSecond >= BUFFER_GROWTH_MS_PER_SECOND) {
            return Trigger.BUFFER_GROWING;
        }
        return Trigger.NONE;
    }

    private static long cooldownRemaining(Request input) {
        if (input.lastAction() == Action.HOLD || input.lastActionAtMs() < 0) return 0;
        long cooldownMs = input.lastAction() == Action.SEEK_LIVE_EDGE
                ? SEEK_COOLDOWN_MS : REBUILD_COOLDOWN_MS;
        long elapsedMs = Math.max(0, input.nowMs() - input.lastActionAtMs());
        return Math.max(0, cooldownMs - elapsedMs);
    }

    private static Decision action(Request input, Trigger trigger, Action action, Reason reason) {
        return new Decision(action, trigger, reason, true, 0,
                input.liveLagMs(), input.lagGrowthMsPerSecond(),
                input.bufferedMs(), input.bufferGrowthMsPerSecond());
    }

    private static Decision hold(Request input, Trigger trigger, Reason reason) {
        return new Decision(Action.HOLD, trigger, reason, false, 0,
                input.liveLagMs(), input.lagGrowthMsPerSecond(),
                input.bufferedMs(), input.bufferGrowthMsPerSecond());
    }

    public enum Action {
        HOLD("hold"),
        SEEK_LIVE_EDGE("seek-live-edge"),
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
        LAG_EXCEEDED("lag-exceeded"),
        LAG_GROWING("lag-growing"),
        BUFFER_GROWING("buffer-growing");

        private final String label;

        Trigger(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Reason {
        NOT_AUTOMATIC_EXO("not-automatic-exo"),
        NOT_RTSP("not-rtsp"),
        NOT_LIVE("not-live"),
        INACTIVE("inactive"),
        STARTUP("startup"),
        USER_SEEK("user-seek"),
        ACTION_PENDING("action-pending"),
        EVIDENCE_UNKNOWN("evidence-unknown"),
        HEALTHY("healthy"),
        CONFIRMING("confirming"),
        COOLDOWN("cooldown"),
        RETRY_LIMIT("retry-limit"),
        SEEK_LIVE_EDGE("seek-live-edge"),
        REBUILD_LAG("rebuild-lag"),
        REBUILD_LAG_GROWTH("rebuild-lag-growth"),
        REBUILD_BUFFER_GROWTH("rebuild-buffer-growth"),
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

    public record Request(
            boolean automatic,
            boolean exo,
            boolean rtsp,
            boolean live,
            boolean active,
            boolean startupComplete,
            boolean userSeeking,
            boolean actionPending,
            boolean liveEdgeReliable,
            boolean seekAvailable,
            long liveLagMs,
            long lagGrowthMsPerSecond,
            long bufferedMs,
            long bufferGrowthMsPerSecond,
            int consecutiveRiskSamples,
            long riskSinceMs,
            int seekAttempts,
            int rebuildAttempts,
            int recoveryAttempts,
            Action lastAction,
            long lastActionAtMs,
            long nowMs) {

        public Request {
            liveLagMs = liveLagMs < 0 ? -1 : liveLagMs;
            bufferedMs = bufferedMs < 0 ? -1 : bufferedMs;
            consecutiveRiskSamples = Math.max(0, consecutiveRiskSamples);
            riskSinceMs = Math.max(-1, riskSinceMs);
            seekAttempts = Math.max(0, seekAttempts);
            rebuildAttempts = Math.max(0, rebuildAttempts);
            recoveryAttempts = Math.max(0, recoveryAttempts);
            lastAction = lastAction == null ? Action.HOLD : lastAction;
            lastActionAtMs = Math.max(-1, lastActionAtMs);
            nowMs = Math.max(0, nowMs);
        }

        static Request inactive() {
            return new Request(false, false, false, false, false, false,
                    false, false, false, false, -1, Long.MIN_VALUE,
                    -1, Long.MIN_VALUE, 0, -1, 0, 0, 0,
                    Action.HOLD, -1, 0);
        }
    }

    public record Decision(
            Action action,
            Trigger trigger,
            Reason reason,
            boolean confirmed,
            long cooldownRemainingMs,
            long liveLagMs,
            long lagGrowthMsPerSecond,
            long bufferedMs,
            long bufferGrowthMsPerSecond) {

        public Decision {
            action = action == null ? Action.HOLD : action;
            trigger = trigger == null ? Trigger.NONE : trigger;
            reason = reason == null ? Reason.EVIDENCE_UNKNOWN : reason;
            cooldownRemainingMs = Math.max(0, cooldownRemainingMs);
        }

        public boolean requestsRecovery() {
            return action != Action.HOLD;
        }
    }
}
