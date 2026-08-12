package com.fongmi.android.tv.player;

import com.fongmi.android.tv.setting.PlaybackProfileAbSetting;

/** Session-isolated collector that commits one bounded comparison sample. */
public final class PlaybackProfileAbCoordinator {

    private final PlaybackProfileAbStore store;
    private final LogSink logSink;
    private final GroupResolver groupResolver;

    private PlaybackAutoContext.SessionToken session =
            PlaybackAutoContext.SessionToken.none();
    private PlaybackProfileAbPolicy.Arm arm;
    private String deviceDigest = "";
    private long experimentGeneration;
    private PlaybackProfileAbPolicy.GroupKey groupKey;
    private boolean active;
    private boolean errorObserved;
    private boolean seenPlaybackIntent;
    private long lastObservationAtElapsedMs = -1;
    private PlaybackAutoContext.PlaybackPhase lastPhase =
            PlaybackAutoContext.PlaybackPhase.UNKNOWN;
    private boolean lastPlaybackIntended;
    private long firstFrameMs = -1;
    private int rebufferCount = -1;
    private long rebufferTotalMs = -1;
    private long activePlaybackMs;
    private long peakPssBytes = -1;
    private long maxDroppedFrames = -1;
    private long maxLiveLagMs = -1;

    public PlaybackProfileAbCoordinator(
            PlaybackProfileAbStore store,
            LogSink logSink) {
        this(store, logSink, PlaybackProfileAbPolicy::resolveGroup);
    }

    public PlaybackProfileAbCoordinator(
            PlaybackProfileAbStore store,
            LogSink logSink,
            GroupResolver groupResolver) {
        this.store = store == null
                ? new PlaybackProfileAbStore(null) : store;
        this.logSink = logSink == null ? entry -> { } : logSink;
        this.groupResolver = groupResolver == null
                ? PlaybackProfileAbPolicy::resolveGroup : groupResolver;
    }

    public static PlaybackProfileAbCoordinator process() {
        return Holder.INSTANCE;
    }

    public synchronized boolean beginSession(
            PlaybackAutoContext.SessionToken session,
            StartConfig config,
            long startedAtElapsedMs) {
        if (active) invalidateLocked(InvalidationReason.REPLACED);
        clearState();
        StartConfig safe = config == null ? StartConfig.disabled() : config;
        if (session == null || !session.active()
                || !safe.allowed()
                || safe.arm() == null
                || !safe.normalPlaybackSpeed()
                || safe.experimentGeneration() <= 0
                || !PlaybackProfileAbIdentity.validDigest(
                safe.deviceDigest())) {
            return false;
        }
        this.session = session;
        this.arm = safe.arm();
        this.deviceDigest = safe.deviceDigest();
        this.experimentGeneration = safe.experimentGeneration();
        this.lastObservationAtElapsedMs = Math.max(0, startedAtElapsedMs);
        this.active = true;
        log("begin", InvalidationReason.NONE,
                "arm=" + arm.label());
        return true;
    }

    public synchronized boolean observe(
            PlaybackAutoContext.SessionToken session,
            RuntimeInput input,
            long sampledAtElapsedMs) {
        if (!isCurrent(session)) return false;
        RuntimeInput safe = input == null
                ? RuntimeInput.disabled() : input;
        if (!safe.allowed()) {
            invalidateLocked(InvalidationReason.POLICY_DISABLED);
            return false;
        }
        if (safe.experimentGeneration() != experimentGeneration) {
            invalidateLocked(InvalidationReason.GENERATION_CHANGED);
            return false;
        }
        if (safe.arm() != arm) {
            invalidateLocked(InvalidationReason.PROFILE_CHANGED);
            return false;
        }
        if (safe.frameSchedulingExperimentActive()) {
            invalidateLocked(InvalidationReason.FRAME_SCHEDULING_CONFOUND);
            return false;
        }
        if (safe.temporarySpeedRescueActive()) {
            invalidateLocked(InvalidationReason.SPEED_RESCUE_CONFOUND);
            return false;
        }
        PlaybackAutoContext context = safe.context();
        if (context == null || !session.equals(context.session())) {
            invalidateLocked(InvalidationReason.SESSION_MISMATCH);
            return false;
        }
        long now = Math.max(0, sampledAtElapsedMs);
        if (lastObservationAtElapsedMs > now) {
            invalidateLocked(InvalidationReason.NON_MONOTONIC_TIME);
            return false;
        }
        PlaybackProfileAbPolicy.GroupResolution group =
                groupResolver.resolve(
                        context, deviceDigest, now);
        if (!group.ready()) {
            if (groupKey != null) {
                invalidateLocked(InvalidationReason.GROUP_FACTS_LOST);
                return false;
            }
        } else if (groupKey == null) {
            groupKey = group.key();
        } else if (!groupKey.equals(group.key())) {
            invalidateLocked(InvalidationReason.GROUP_CHANGED);
            return false;
        }
        PlaybackTelemetry.RuntimeObservation observation = safe.observation();
        PlaybackAutoContext.PlaybackPhase phase = metricPhase(observation);
        if (seenPlaybackIntent
                && !safe.playbackIntended()
                && phase == PlaybackAutoContext.PlaybackPhase.READY) {
            invalidateLocked(InvalidationReason.PAUSED);
            return false;
        }
        accumulateActiveDuration(now);
        if (safe.playbackIntended()) seenPlaybackIntent = true;
        observeMetrics(context, observation, now);
        lastObservationAtElapsedMs = now;
        lastPhase = phase;
        lastPlaybackIntended = safe.playbackIntended();
        return true;
    }

    public synchronized boolean endSession(
            PlaybackAutoContext.SessionToken session,
            EndConfig config,
            long endedAtElapsedMs,
            long nowEpochMs) {
        if (!isCurrent(session)) return false;
        EndConfig safe = config == null ? EndConfig.disabled() : config;
        if (!safe.allowed()) {
            invalidateLocked(InvalidationReason.POLICY_DISABLED);
            return false;
        }
        if (safe.experimentGeneration() != experimentGeneration) {
            invalidateLocked(InvalidationReason.GENERATION_CHANGED);
            return false;
        }
        if (safe.arm() != arm) {
            invalidateLocked(InvalidationReason.PROFILE_CHANGED);
            return false;
        }
        long endedAt = Math.max(0, endedAtElapsedMs);
        if (endedAt >= lastObservationAtElapsedMs) {
            accumulateActiveDuration(endedAt);
        }
        if (groupKey == null) {
            invalidateLocked(InvalidationReason.GROUP_INCOMPLETE);
            return false;
        }
        boolean successfulSample = firstFrameMs >= 0
                && activePlaybackMs
                >= PlaybackProfileAbPolicy.MIN_SUCCESSFUL_ACTIVE_PLAYBACK_MS;
        if (!errorObserved && !successfulSample) {
            invalidateLocked(InvalidationReason.SESSION_TOO_SHORT);
            return false;
        }
        PlaybackProfileAbStore.Sample sample =
                new PlaybackProfileAbStore.Sample(
                        PlaybackProfileAbStore.SAMPLE_VERSION,
                        groupKey,
                        arm,
                        firstFrameMs,
                        rebufferCount,
                        rebufferTotalMs,
                        activePlaybackMs,
                        peakPssBytes,
                        maxDroppedFrames,
                        maxLiveLagMs,
                        errorObserved,
                        Math.max(1, nowEpochMs));
        boolean recorded = store.record(sample, Math.max(1, nowEpochMs));
        log(recorded ? "record" : "record-skipped",
                InvalidationReason.NONE,
                "arm=" + arm.label()
                        + " group="
                        + PlaybackProfileAbIdentity.shortDigest(
                        PlaybackProfileAbIdentity.groupDigest(groupKey))
                        + " firstFrameMs=" + firstFrameMs
                        + " activeMs=" + activePlaybackMs
                        + " rebufferCount=" + rebufferCount
                        + " error=" + errorObserved
                        + " end=" + PlaybackTelemetry.safeLabel(
                        safe.reason()));
        clearState();
        return recorded;
    }

    public synchronized void invalidate(
            PlaybackAutoContext.SessionToken session,
            InvalidationReason reason) {
        if (!isCurrent(session)) return;
        invalidateLocked(reason);
    }

    public synchronized void invalidateActive(InvalidationReason reason) {
        if (!active) return;
        invalidateLocked(reason);
    }

    public synchronized SessionSnapshot snapshot() {
        return new SessionSnapshot(
                session,
                active,
                arm,
                groupKey,
                experimentGeneration,
                firstFrameMs,
                activePlaybackMs,
                errorObserved);
    }

    private void observeMetrics(
            PlaybackAutoContext context,
            PlaybackTelemetry.RuntimeObservation observation,
            long now) {
        if (observation != null) {
            if (observation.firstFrameElapsedMs().known()) {
                long value = Math.max(0,
                        observation.firstFrameElapsedMs().value());
                firstFrameMs = firstFrameMs < 0
                        ? value : Math.min(firstFrameMs, value);
            }
            if (observation.rebufferCount().known()) {
                rebufferCount = Math.max(rebufferCount,
                        Math.max(0, observation.rebufferCount().value()));
            }
            if (observation.rebufferTotalMs().known()) {
                rebufferTotalMs = Math.max(rebufferTotalMs,
                        Math.max(0, observation.rebufferTotalMs().value()));
            }
            if (observation.droppedFrames().known()) {
                maxDroppedFrames = Math.max(maxDroppedFrames,
                        Math.max(0, observation.droppedFrames().value()));
            }
            if (observation.liveLagMs().known()) {
                maxLiveLagMs = Math.max(maxLiveLagMs,
                        Math.max(0, observation.liveLagMs().value()));
            }
            if (observation.phase().known()
                    && observation.phase().value()
                    == PlaybackAutoContext.PlaybackPhase.ERROR) {
                errorObserved = true;
            }
        }
        PlaybackAutoContext.Fact<Long> pss =
                context.device().diagnosticPssBytes();
        if (pss.isUsable(now) && pss.value() >= 0) {
            peakPssBytes = Math.max(peakPssBytes, pss.value());
        }
    }

    private void accumulateActiveDuration(long now) {
        if (lastObservationAtElapsedMs < 0
                || now <= lastObservationAtElapsedMs) return;
        if (firstFrameMs < 0
                || !lastPlaybackIntended
                || lastPhase != PlaybackAutoContext.PlaybackPhase.READY
                && lastPhase
                != PlaybackAutoContext.PlaybackPhase.BUFFERING) return;
        activePlaybackMs = saturatingAdd(
                activePlaybackMs,
                now - lastObservationAtElapsedMs);
    }

    private static PlaybackAutoContext.PlaybackPhase metricPhase(
            PlaybackTelemetry.RuntimeObservation observation) {
        return observation != null && observation.phase().known()
                ? observation.phase().value()
                : PlaybackAutoContext.PlaybackPhase.UNKNOWN;
    }

    private boolean isCurrent(PlaybackAutoContext.SessionToken candidate) {
        return active && candidate != null && candidate.active()
                && candidate.equals(session);
    }

    private void invalidateLocked(InvalidationReason reason) {
        InvalidationReason safe = reason == null
                ? InvalidationReason.UNKNOWN : reason;
        log("invalidate", safe,
                "arm=" + (arm == null ? "none" : arm.label()));
        clearState();
    }

    private void clearState() {
        session = PlaybackAutoContext.SessionToken.none();
        arm = null;
        deviceDigest = "";
        experimentGeneration = 0;
        groupKey = null;
        active = false;
        errorObserved = false;
        seenPlaybackIntent = false;
        lastObservationAtElapsedMs = -1;
        lastPhase = PlaybackAutoContext.PlaybackPhase.UNKNOWN;
        lastPlaybackIntended = false;
        firstFrameMs = -1;
        rebufferCount = -1;
        rebufferTotalMs = -1;
        activePlaybackMs = 0;
        peakPssBytes = -1;
        maxDroppedFrames = -1;
        maxLiveLagMs = -1;
    }

    private void log(
            String action,
            InvalidationReason reason,
            String details) {
        logSink.log(new LogEntry(
                session.traceId(),
                "action=" + PlaybackTelemetry.safeLabel(action)
                        + " reason=" + (reason == null
                        ? InvalidationReason.UNKNOWN.label
                        : reason.label)
                        + " " + (details == null ? "" : details)));
    }

    private static long saturatingAdd(long first, long second) {
        if (second <= 0) return Math.max(0, first);
        return first > Long.MAX_VALUE - second
                ? Long.MAX_VALUE : Math.max(0, first) + second;
    }

    public enum InvalidationReason {
        NONE("none"),
        REPLACED("replaced"),
        POLICY_DISABLED("policy-disabled"),
        GENERATION_CHANGED("generation-changed"),
        PROFILE_CHANGED("profile-changed"),
        SESSION_MISMATCH("session-mismatch"),
        FRAME_SCHEDULING_CONFOUND("frame-scheduling-confound"),
        SPEED_RESCUE_CONFOUND("speed-rescue-confound"),
        USER_SEEK("user-seek"),
        PAUSED("paused"),
        USER_SPEED("user-speed"),
        GROUP_CHANGED("group-changed"),
        GROUP_FACTS_LOST("group-facts-lost"),
        GROUP_INCOMPLETE("group-incomplete"),
        NON_MONOTONIC_TIME("non-monotonic-time"),
        SESSION_TOO_SHORT("session-too-short"),
        ENROLLMENT_CHANGED("enrollment-changed"),
        UNKNOWN("unknown");

        private final String label;

        InvalidationReason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record StartConfig(
            boolean allowed,
            PlaybackProfileAbPolicy.Arm arm,
            String deviceDigest,
            long experimentGeneration,
            boolean normalPlaybackSpeed) {

        public StartConfig {
            deviceDigest = deviceDigest == null ? "" : deviceDigest;
        }

        public static StartConfig disabled() {
            return new StartConfig(false, null, "", 0, false);
        }
    }

    public record RuntimeInput(
            boolean allowed,
            PlaybackProfileAbPolicy.Arm arm,
            long experimentGeneration,
            PlaybackAutoContext context,
            PlaybackTelemetry.RuntimeObservation observation,
            boolean playbackIntended,
            boolean frameSchedulingExperimentActive,
            boolean temporarySpeedRescueActive) {

        public static RuntimeInput disabled() {
            return new RuntimeInput(
                    false,
                    null,
                    0,
                    null,
                    PlaybackTelemetry.RuntimeObservation.unknown(),
                    false,
                    false,
                    false);
        }
    }

    public record EndConfig(
            boolean allowed,
            PlaybackProfileAbPolicy.Arm arm,
            long experimentGeneration,
            String reason) {

        public EndConfig {
            reason = PlaybackTelemetry.safeLabel(reason);
        }

        public static EndConfig disabled() {
            return new EndConfig(false, null, 0, "disabled");
        }
    }

    public record SessionSnapshot(
            PlaybackAutoContext.SessionToken session,
            boolean active,
            PlaybackProfileAbPolicy.Arm arm,
            PlaybackProfileAbPolicy.GroupKey groupKey,
            long experimentGeneration,
            long firstFrameMs,
            long activePlaybackMs,
            boolean errorObserved) {
    }

    public record LogEntry(String traceId, String message) {

        public LogEntry {
            traceId = PlaybackTrace.normalize(traceId);
            message = message == null ? "" : message;
        }
    }

    @FunctionalInterface
    public interface LogSink {
        void log(LogEntry entry);
    }

    @FunctionalInterface
    public interface GroupResolver {
        PlaybackProfileAbPolicy.GroupResolution resolve(
                PlaybackAutoContext context,
                String deviceDigest,
                long elapsedRealtimeMs);
    }

    private static final class Holder {

        private static final PlaybackProfileAbCoordinator INSTANCE =
                new PlaybackProfileAbCoordinator(
                        PlaybackProfileAbSetting.store(),
                        entry -> PlaybackTrace.log(
                                "playback-profile-ab",
                                entry.traceId(),
                                "%s",
                                entry.message()));
    }
}
