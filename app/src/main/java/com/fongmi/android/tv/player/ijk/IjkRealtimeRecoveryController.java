package com.fongmi.android.tv.player.ijk;

import com.fongmi.android.tv.player.PlaybackAutoContext;

import java.util.ArrayDeque;
import java.util.Deque;

/** Session-isolated sampler for conservative IJK RTSP/RTMP backlog recovery. */
public final class IjkRealtimeRecoveryController {

    public static final long MIN_SAMPLE_INTERVAL_MS = 4_000L;
    public static final long MIN_GROWTH_WINDOW_MS = 10_000L;
    public static final long MAX_CONSECUTIVE_SAMPLE_GAP_MS = 15_000L;
    public static final long MAX_SAMPLE_WINDOW_MS = 45_000L;
    public static final long USER_SEEK_SUPPRESSION_MS = 15_000L;

    private static final long UNKNOWN_SLOPE = Long.MIN_VALUE;

    private final Deque<Sample> samples = new ArrayDeque<>();
    private PlaybackAutoContext.SessionToken session =
            PlaybackAutoContext.SessionToken.none();
    private IjkRealtimeRecoveryPolicy.Trigger riskTrigger =
            IjkRealtimeRecoveryPolicy.Trigger.NONE;
    private int consecutiveRiskSamples;
    private int recoveryAttempts;
    private int successfulRecoveries;
    private int failedRecoveries;
    private long riskSinceMs = -1;
    private long userSeekSuppressedUntilMs = -1;
    private long lastEvaluatedAtMs = -1;
    private long actionStartedAtMs = -1;
    private boolean actionPending;

    public synchronized void beginSession(
            PlaybackAutoContext.SessionToken token) {
        resetAll(token);
    }

    public synchronized void endSession(
            PlaybackAutoContext.SessionToken token) {
        if (!isCurrent(token)) return;
        resetAll(PlaybackAutoContext.SessionToken.none());
    }

    public synchronized void onUserSeek(
            PlaybackAutoContext.SessionToken token,
            long nowMs) {
        if (!isCurrent(token)) return;
        long now = Math.max(0, nowMs);
        userSeekSuppressedUntilMs = saturatingAdd(
                now, USER_SEEK_SUPPRESSION_MS);
        clearObservations();
    }

    public synchronized void onPositionDiscontinuity(
            PlaybackAutoContext.SessionToken token) {
        if (!isCurrent(token)) return;
        clearObservations();
    }

    public synchronized void onPlaybackError(
            PlaybackAutoContext.SessionToken token) {
        if (!isCurrent(token)) return;
        clearObservations();
    }

    public synchronized IjkRealtimeRecoveryPolicy.Decision evaluate(
            Input input) {
        Input current = input == null ? Input.inactive() : input;
        if (!isCurrent(current.session())) {
            return hold(IjkRealtimeRecoveryPolicy.Reason.STALE_SESSION,
                    current);
        }
        if (lastEvaluatedAtMs >= 0 && current.nowMs() < lastEvaluatedAtMs) {
            return hold(IjkRealtimeRecoveryPolicy.Reason.STALE_SAMPLE,
                    current);
        }
        lastEvaluatedAtMs = current.nowMs();

        boolean userSeeking = current.userSeeking()
                || current.nowMs() < userSeekSuppressedUntilMs;
        boolean eligibleForSampling = current.automatic()
                && current.ijk()
                && current.protocolUsable()
                && isRealtime(current.protocol())
                && current.active()
                && current.startupComplete()
                && current.normalSpeed()
                && !userSeeking
                && !actionPending
                && !current.reloadActionPending()
                && current.queue().durationUsable();
        if (!eligibleForSampling) {
            clearObservations();
        } else if (shouldAddSample(current.nowMs())) {
            addSample(current.nowMs(), current.queue());
            updateRisk(current.queue(), current.appliedConfig());
        } else if (currentRisk(current.queue(), current.appliedConfig())
                == IjkRealtimeRecoveryPolicy.Trigger.NONE) {
            clearObservations();
        }

        Growth growth = growth();
        return IjkRealtimeRecoveryPolicy.resolve(
                new IjkRealtimeRecoveryPolicy.Request(
                        current.automatic(),
                        current.ijk(),
                        current.protocolUsable(),
                        current.protocol(),
                        current.active(),
                        current.startupComplete(),
                        current.normalSpeed(),
                        userSeeking,
                        actionPending || current.reloadActionPending(),
                        current.queue(),
                        current.appliedConfig(),
                        growth.durationMsPerSecond(),
                        growth.bytesPerSecond(),
                        growth.packetsPerSecond(),
                        consecutiveRiskSamples,
                        riskSinceMs,
                        current.nowMs()));
    }

    public synchronized boolean beginAction(
            PlaybackAutoContext.SessionToken token,
            IjkRealtimeRecoveryPolicy.Decision decision,
            long nowMs) {
        if (!isCurrent(token) || decision == null
                || !decision.requestsRecovery() || actionPending) {
            return false;
        }
        actionPending = true;
        actionStartedAtMs = Math.max(0, nowMs);
        if (recoveryAttempts < Integer.MAX_VALUE) recoveryAttempts++;
        userSeekSuppressedUntilMs = -1;
        clearObservations();
        return true;
    }

    public synchronized void completeAction(
            PlaybackAutoContext.SessionToken token,
            boolean succeeded) {
        if (!isCurrent(token) || !actionPending) return;
        actionPending = false;
        if (succeeded) {
            if (successfulRecoveries < Integer.MAX_VALUE) {
                successfulRecoveries++;
            }
        } else if (failedRecoveries < Integer.MAX_VALUE) {
            failedRecoveries++;
        }
    }

    public synchronized Snapshot snapshot() {
        Growth growth = growth();
        return new Snapshot(
                session,
                riskTrigger,
                consecutiveRiskSamples,
                recoveryAttempts,
                successfulRecoveries,
                failedRecoveries,
                riskSinceMs,
                userSeekSuppressedUntilMs,
                lastEvaluatedAtMs,
                actionStartedAtMs,
                actionPending,
                growth.durationMsPerSecond(),
                growth.bytesPerSecond(),
                growth.packetsPerSecond());
    }

    private void updateRisk(
            IjkRealtimeRecoveryPolicy.QueueSnapshot queue,
            IjkBufferPolicy.Config config) {
        IjkRealtimeRecoveryPolicy.Trigger current = currentRisk(queue, config);
        if (current == IjkRealtimeRecoveryPolicy.Trigger.NONE) {
            riskTrigger = current;
            consecutiveRiskSamples = 0;
            riskSinceMs = -1;
            return;
        }
        long now = samples.isEmpty() ? 0 : samples.peekLast().nowMs();
        if (riskTrigger == IjkRealtimeRecoveryPolicy.Trigger.NONE) {
            consecutiveRiskSamples = 1;
            riskSinceMs = now;
        } else if (consecutiveRiskSamples < Integer.MAX_VALUE) {
            consecutiveRiskSamples++;
        }
        riskTrigger = current;
    }

    private IjkRealtimeRecoveryPolicy.Trigger currentRisk(
            IjkRealtimeRecoveryPolicy.QueueSnapshot queue,
            IjkBufferPolicy.Config config) {
        Growth growth = growth();
        return IjkRealtimeRecoveryPolicy.classifyRisk(
                queue,
                growth.durationMsPerSecond(),
                growth.bytesPerSecond(),
                growth.packetsPerSecond(),
                IjkRealtimeRecoveryPolicy.thresholds(config));
    }

    private boolean shouldAddSample(long nowMs) {
        Sample last = samples.peekLast();
        return last == null
                || nowMs - last.nowMs() >= MIN_SAMPLE_INTERVAL_MS;
    }

    private void addSample(
            long nowMs,
            IjkRealtimeRecoveryPolicy.QueueSnapshot queue) {
        long now = Math.max(0, nowMs);
        IjkRealtimeRecoveryPolicy.QueueSnapshot safe = queue == null
                ? IjkRealtimeRecoveryPolicy.QueueSnapshot.unknown() : queue;
        Sample previous = samples.peekLast();
        if (previous != null
                && now - previous.nowMs()
                > MAX_CONSECUTIVE_SAMPLE_GAP_MS) {
            clearObservations();
        }
        samples.addLast(new Sample(
                now,
                safe.playableDurationMs(),
                safe.totalBytes(),
                safe.totalPackets()));
        while (samples.size() > 1
                && now - samples.peekFirst().nowMs()
                > MAX_SAMPLE_WINDOW_MS) {
            samples.removeFirst();
        }
    }

    private Growth growth() {
        return new Growth(slope(Value.DURATION), slope(Value.BYTES),
                slope(Value.PACKETS));
    }

    private long slope(Value value) {
        Sample first = null;
        Sample last = null;
        for (Sample sample : samples) {
            long observed = sample.value(value);
            if (observed < 0) continue;
            if (first == null) first = sample;
            last = sample;
        }
        if (first == null || last == null || first == last) {
            return UNKNOWN_SLOPE;
        }
        long elapsedMs = last.nowMs() - first.nowMs();
        if (elapsedMs < MIN_GROWTH_WINDOW_MS) return UNKNOWN_SLOPE;
        double slope = (last.value(value) - first.value(value))
                * 1_000d / elapsedMs;
        if (!Double.isFinite(slope)) return UNKNOWN_SLOPE;
        if (slope >= Long.MAX_VALUE) return Long.MAX_VALUE;
        if (slope <= Long.MIN_VALUE) return Long.MIN_VALUE;
        return Math.round(slope);
    }

    private IjkRealtimeRecoveryPolicy.Decision hold(
            IjkRealtimeRecoveryPolicy.Reason reason,
            Input input) {
        Growth growth = growth();
        return new IjkRealtimeRecoveryPolicy.Decision(
                IjkRealtimeRecoveryPolicy.Action.HOLD,
                riskTrigger,
                reason,
                false,
                IjkRealtimeRecoveryPolicy.thresholds(input.appliedConfig()),
                input.queue(),
                growth.durationMsPerSecond(),
                growth.bytesPerSecond(),
                growth.packetsPerSecond());
    }

    private boolean isCurrent(PlaybackAutoContext.SessionToken token) {
        return token != null && token.active() && token.equals(session);
    }

    private static boolean isRealtime(PlaybackAutoContext.Protocol protocol) {
        return protocol == PlaybackAutoContext.Protocol.RTSP
                || protocol == PlaybackAutoContext.Protocol.RTMP;
    }

    private void clearObservations() {
        samples.clear();
        riskTrigger = IjkRealtimeRecoveryPolicy.Trigger.NONE;
        consecutiveRiskSamples = 0;
        riskSinceMs = -1;
    }

    private void resetAll(PlaybackAutoContext.SessionToken token) {
        session = token == null
                ? PlaybackAutoContext.SessionToken.none() : token;
        clearObservations();
        recoveryAttempts = 0;
        successfulRecoveries = 0;
        failedRecoveries = 0;
        userSeekSuppressedUntilMs = -1;
        lastEvaluatedAtMs = -1;
        actionStartedAtMs = -1;
        actionPending = false;
    }

    private static long saturatingAdd(long first, long second) {
        long safeFirst = Math.max(0, first);
        long safeSecond = Math.max(0, second);
        return safeFirst > Long.MAX_VALUE - safeSecond
                ? Long.MAX_VALUE : safeFirst + safeSecond;
    }

    private enum Value {
        DURATION,
        BYTES,
        PACKETS
    }

    public record Input(
            PlaybackAutoContext.SessionToken session,
            boolean automatic,
            boolean ijk,
            boolean protocolUsable,
            PlaybackAutoContext.Protocol protocol,
            boolean active,
            boolean startupComplete,
            boolean normalSpeed,
            boolean userSeeking,
            boolean reloadActionPending,
            IjkRealtimeRecoveryPolicy.QueueSnapshot queue,
            IjkBufferPolicy.Config appliedConfig,
            long nowMs) {

        public Input {
            session = session == null
                    ? PlaybackAutoContext.SessionToken.none() : session;
            protocol = protocol == null
                    ? PlaybackAutoContext.Protocol.UNKNOWN : protocol;
            queue = queue == null
                    ? IjkRealtimeRecoveryPolicy.QueueSnapshot.unknown() : queue;
            appliedConfig = appliedConfig == null
                    ? IjkBufferPolicy.safeInitialConfig() : appliedConfig;
            nowMs = Math.max(0, nowMs);
        }

        static Input inactive() {
            return new Input(PlaybackAutoContext.SessionToken.none(),
                    false, false, false,
                    PlaybackAutoContext.Protocol.UNKNOWN, false, false,
                    false, false, false,
                    IjkRealtimeRecoveryPolicy.QueueSnapshot.unknown(),
                    IjkBufferPolicy.safeInitialConfig(), 0);
        }
    }

    public record Snapshot(
            PlaybackAutoContext.SessionToken session,
            IjkRealtimeRecoveryPolicy.Trigger riskTrigger,
            int consecutiveRiskSamples,
            int recoveryAttempts,
            int successfulRecoveries,
            int failedRecoveries,
            long riskSinceMs,
            long userSeekSuppressedUntilMs,
            long lastEvaluatedAtMs,
            long actionStartedAtMs,
            boolean actionPending,
            long durationGrowthMsPerSecond,
            long bytesGrowthPerSecond,
            long packetsGrowthPerSecond) {
    }

    private record Sample(
            long nowMs,
            long durationMs,
            long bytes,
            long packets) {

        long value(Value value) {
            return switch (value) {
                case DURATION -> durationMs;
                case BYTES -> bytes;
                case PACKETS -> packets;
            };
        }
    }

    private record Growth(
            long durationMsPerSecond,
            long bytesPerSecond,
            long packetsPerSecond) {
    }
}
