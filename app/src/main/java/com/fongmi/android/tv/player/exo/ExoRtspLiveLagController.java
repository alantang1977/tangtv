package com.fongmi.android.tv.player.exo;

import com.fongmi.android.tv.player.PlaybackAutoContext;

import java.util.ArrayDeque;
import java.util.Deque;

/** Session-isolated sampler and retry state for {@link ExoRtspLiveLagPolicy}. */
public final class ExoRtspLiveLagController {

    public static final long MIN_SAMPLE_INTERVAL_MS = 4_000L;
    public static final long MIN_GROWTH_WINDOW_MS = 10_000L;
    public static final long MAX_SAMPLE_WINDOW_MS = 45_000L;
    public static final long USER_SEEK_SUPPRESSION_MS = 15_000L;

    private static final long UNKNOWN_SLOPE = Long.MIN_VALUE;

    private final Deque<Sample> samples = new ArrayDeque<>();
    private PlaybackAutoContext.SessionToken session = PlaybackAutoContext.SessionToken.none();
    private ExoRtspLiveLagPolicy.Trigger riskTrigger = ExoRtspLiveLagPolicy.Trigger.NONE;
    private ExoRtspLiveLagPolicy.Action pendingAction = ExoRtspLiveLagPolicy.Action.HOLD;
    private ExoRtspLiveLagPolicy.Action lastAction = ExoRtspLiveLagPolicy.Action.HOLD;
    private int consecutiveRiskSamples;
    private int seekAttempts;
    private int rebuildAttempts;
    private int recoveryAttempts;
    private long riskSinceMs = -1;
    private long pendingActionAtMs = -1;
    private long lastActionAtMs = -1;
    private long userSeekSuppressedUntilMs = -1;
    private long lastEvaluatedAtMs = -1;
    private boolean lastActionSucceeded;

    public synchronized void beginSession(PlaybackAutoContext.SessionToken token) {
        resetAll(token);
    }

    public synchronized void endSession(PlaybackAutoContext.SessionToken token) {
        if (token == null || !token.equals(session)) return;
        resetAll(PlaybackAutoContext.SessionToken.none());
    }

    public synchronized void reset() {
        resetAll(PlaybackAutoContext.SessionToken.none());
    }

    public synchronized void onUserSeek(PlaybackAutoContext.SessionToken token, long nowMs) {
        if (!isCurrent(token)) return;
        long now = Math.max(0, nowMs);
        userSeekSuppressedUntilMs = saturatingAdd(now, USER_SEEK_SUPPRESSION_MS);
        clearObservations();
        pendingAction = ExoRtspLiveLagPolicy.Action.HOLD;
        pendingActionAtMs = -1;
    }

    public synchronized void onPositionDiscontinuity(PlaybackAutoContext.SessionToken token) {
        if (!isCurrent(token)) return;
        clearObservations();
        pendingAction = ExoRtspLiveLagPolicy.Action.HOLD;
        pendingActionAtMs = -1;
    }

    public synchronized void onPlaybackError(PlaybackAutoContext.SessionToken token) {
        onPositionDiscontinuity(token);
    }

    public synchronized ExoRtspLiveLagPolicy.Decision evaluate(Input input) {
        Input current = input == null ? Input.inactive() : input;
        if (!isCurrent(current.session())) {
            return hold(ExoRtspLiveLagPolicy.Reason.STALE_SESSION, current);
        }
        if (lastEvaluatedAtMs >= 0 && current.nowMs() < lastEvaluatedAtMs) {
            return hold(ExoRtspLiveLagPolicy.Reason.STALE_SAMPLE, current);
        }
        lastEvaluatedAtMs = current.nowMs();

        boolean userSeeking = current.userSeeking()
                || current.nowMs() < userSeekSuppressedUntilMs;
        boolean eligibleForSampling = current.automatic()
                && current.exo()
                && current.rtsp()
                && current.live()
                && current.active()
                && current.startupComplete()
                && !userSeeking;
        if (!eligibleForSampling) {
            clearObservations();
        } else if (shouldAddSample(current.nowMs())) {
            addSample(current.nowMs(), current.liveLagMs(), current.bufferedMs());
            updateRisk(current.liveLagMs(), current.bufferedMs());
        }

        Growth growth = growth();
        ExoRtspLiveLagPolicy.Request request = new ExoRtspLiveLagPolicy.Request(
                current.automatic(),
                current.exo(),
                current.rtsp(),
                current.live(),
                current.active(),
                current.startupComplete(),
                userSeeking,
                pendingAction != ExoRtspLiveLagPolicy.Action.HOLD,
                current.liveEdgeReliable(),
                current.seekAvailable(),
                current.liveLagMs(),
                growth.lagMsPerSecond(),
                current.bufferedMs(),
                growth.bufferMsPerSecond(),
                consecutiveRiskSamples,
                riskSinceMs,
                seekAttempts,
                rebuildAttempts,
                recoveryAttempts,
                lastAction,
                lastActionAtMs,
                current.nowMs());
        ExoRtspLiveLagPolicy.Decision decision = ExoRtspLiveLagPolicy.resolve(request);
        if (decision.requestsRecovery()) {
            pendingAction = decision.action();
            pendingActionAtMs = current.nowMs();
        }
        return decision;
    }

    /**
     * Commits an action before touching Media3 so synchronous player callbacks cannot request
     * another recovery. A failed action still consumes one bounded attempt.
     */
    public synchronized boolean beginAction(PlaybackAutoContext.SessionToken token,
                                            ExoRtspLiveLagPolicy.Action action,
                                            long nowMs) {
        if (!isCurrent(token) || action == null || action == ExoRtspLiveLagPolicy.Action.HOLD
                || action != pendingAction) {
            return false;
        }
        long now = Math.max(0, nowMs);
        if (action == ExoRtspLiveLagPolicy.Action.SEEK_LIVE_EDGE) seekAttempts++;
        if (action == ExoRtspLiveLagPolicy.Action.REBUILD_SESSION) rebuildAttempts++;
        recoveryAttempts++;
        lastAction = action;
        lastActionAtMs = now;
        lastActionSucceeded = false;
        pendingAction = ExoRtspLiveLagPolicy.Action.HOLD;
        pendingActionAtMs = -1;
        userSeekSuppressedUntilMs = -1;
        clearObservations();
        return true;
    }

    public synchronized void completeAction(PlaybackAutoContext.SessionToken token,
                                            ExoRtspLiveLagPolicy.Action action,
                                            boolean succeeded) {
        if (!isCurrent(token) || action == null || action != lastAction) return;
        lastActionSucceeded = succeeded;
    }

    public synchronized Snapshot snapshot() {
        Growth growth = growth();
        return new Snapshot(
                session,
                riskTrigger,
                pendingAction,
                lastAction,
                consecutiveRiskSamples,
                seekAttempts,
                rebuildAttempts,
                recoveryAttempts,
                riskSinceMs,
                pendingActionAtMs,
                lastActionAtMs,
                userSeekSuppressedUntilMs,
                growth.lagMsPerSecond(),
                growth.bufferMsPerSecond(),
                lastActionSucceeded);
    }

    private void updateRisk(long liveLagMs, long bufferedMs) {
        Growth growth = growth();
        ExoRtspLiveLagPolicy.Trigger current = ExoRtspLiveLagPolicy.classifyRisk(
                liveLagMs, growth.lagMsPerSecond(), bufferedMs, growth.bufferMsPerSecond());
        if (current == ExoRtspLiveLagPolicy.Trigger.NONE) {
            riskTrigger = current;
            consecutiveRiskSamples = 0;
            riskSinceMs = -1;
            return;
        }
        long now = samples.isEmpty() ? 0 : samples.peekLast().nowMs();
        if (current != riskTrigger) {
            riskTrigger = current;
            consecutiveRiskSamples = 1;
            riskSinceMs = now;
            return;
        }
        if (consecutiveRiskSamples < Integer.MAX_VALUE) consecutiveRiskSamples++;
    }

    private boolean shouldAddSample(long nowMs) {
        Sample last = samples.peekLast();
        return last == null || nowMs - last.nowMs() >= MIN_SAMPLE_INTERVAL_MS;
    }

    private void addSample(long nowMs, long liveLagMs, long bufferedMs) {
        long now = Math.max(0, nowMs);
        samples.addLast(new Sample(now, liveLagMs < 0 ? -1 : liveLagMs,
                bufferedMs < 0 ? -1 : bufferedMs));
        while (samples.size() > 2 && now - samples.peekFirst().nowMs() > MAX_SAMPLE_WINDOW_MS) {
            samples.removeFirst();
        }
    }

    private Growth growth() {
        return new Growth(slope(true), slope(false));
    }

    private long slope(boolean lag) {
        Sample first = null;
        Sample last = null;
        for (Sample sample : samples) {
            long value = lag ? sample.liveLagMs() : sample.bufferedMs();
            if (value < 0) continue;
            if (first == null) first = sample;
            last = sample;
        }
        if (first == null || last == null || first == last) return UNKNOWN_SLOPE;
        long elapsedMs = last.nowMs() - first.nowMs();
        if (elapsedMs < MIN_GROWTH_WINDOW_MS) return UNKNOWN_SLOPE;
        long firstValue = lag ? first.liveLagMs() : first.bufferedMs();
        long lastValue = lag ? last.liveLagMs() : last.bufferedMs();
        double slope = (lastValue - firstValue) * 1_000d / elapsedMs;
        if (!Double.isFinite(slope)) return UNKNOWN_SLOPE;
        if (slope >= Long.MAX_VALUE) return Long.MAX_VALUE;
        if (slope <= Long.MIN_VALUE) return Long.MIN_VALUE;
        return Math.round(slope);
    }

    private ExoRtspLiveLagPolicy.Decision hold(ExoRtspLiveLagPolicy.Reason reason, Input input) {
        Growth growth = growth();
        return new ExoRtspLiveLagPolicy.Decision(
                ExoRtspLiveLagPolicy.Action.HOLD,
                riskTrigger,
                reason,
                false,
                0,
                input.liveLagMs(),
                growth.lagMsPerSecond(),
                input.bufferedMs(),
                growth.bufferMsPerSecond());
    }

    private boolean isCurrent(PlaybackAutoContext.SessionToken token) {
        return token != null && token.active() && token.equals(session);
    }

    private void clearObservations() {
        samples.clear();
        riskTrigger = ExoRtspLiveLagPolicy.Trigger.NONE;
        consecutiveRiskSamples = 0;
        riskSinceMs = -1;
    }

    private void resetAll(PlaybackAutoContext.SessionToken token) {
        session = token == null ? PlaybackAutoContext.SessionToken.none() : token;
        clearObservations();
        pendingAction = ExoRtspLiveLagPolicy.Action.HOLD;
        lastAction = ExoRtspLiveLagPolicy.Action.HOLD;
        seekAttempts = 0;
        rebuildAttempts = 0;
        recoveryAttempts = 0;
        pendingActionAtMs = -1;
        lastActionAtMs = -1;
        userSeekSuppressedUntilMs = -1;
        lastEvaluatedAtMs = -1;
        lastActionSucceeded = false;
    }

    private static long saturatingAdd(long first, long second) {
        long safeFirst = Math.max(0, first);
        long safeSecond = Math.max(0, second);
        return safeFirst > Long.MAX_VALUE - safeSecond
                ? Long.MAX_VALUE : safeFirst + safeSecond;
    }

    public record Input(
            PlaybackAutoContext.SessionToken session,
            boolean automatic,
            boolean exo,
            boolean rtsp,
            boolean live,
            boolean active,
            boolean startupComplete,
            boolean userSeeking,
            boolean loading,
            boolean liveEdgeReliable,
            boolean seekAvailable,
            long liveLagMs,
            long bufferedMs,
            long nowMs) {

        public Input {
            session = session == null ? PlaybackAutoContext.SessionToken.none() : session;
            liveLagMs = liveLagMs < 0 ? -1 : liveLagMs;
            bufferedMs = bufferedMs < 0 ? -1 : bufferedMs;
            nowMs = Math.max(0, nowMs);
        }

        static Input inactive() {
            return new Input(PlaybackAutoContext.SessionToken.none(), false, false,
                    false, false, false, false, false, false,
                    false, false, -1, -1, 0);
        }
    }

    public record Snapshot(
            PlaybackAutoContext.SessionToken session,
            ExoRtspLiveLagPolicy.Trigger riskTrigger,
            ExoRtspLiveLagPolicy.Action pendingAction,
            ExoRtspLiveLagPolicy.Action lastAction,
            int consecutiveRiskSamples,
            int seekAttempts,
            int rebuildAttempts,
            int recoveryAttempts,
            long riskSinceMs,
            long pendingActionAtMs,
            long lastActionAtMs,
            long userSeekSuppressedUntilMs,
            long lagGrowthMsPerSecond,
            long bufferGrowthMsPerSecond,
            boolean lastActionSucceeded) {
    }

    private record Sample(long nowMs, long liveLagMs, long bufferedMs) {
    }

    private record Growth(long lagMsPerSecond, long bufferMsPerSecond) {
    }
}
