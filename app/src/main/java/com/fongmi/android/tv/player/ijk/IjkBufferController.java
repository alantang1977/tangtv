package com.fongmi.android.tv.player.ijk;

import com.fongmi.android.tv.player.PlaybackAutoContext;

/** Session-safe controller that stages IJK options and gates all managed IJK reloads. */
public final class IjkBufferController {

    public static final long RELOAD_COOLDOWN_MS = 30_000L;
    public static final long EARLY_SCENE_WINDOW_MS = 20_000L;
    public static final int MAX_RELOAD_ATTEMPTS = 3;

    private PlaybackAutoContext.SessionToken session =
            PlaybackAutoContext.SessionToken.none();
    private long startedAtElapsedMs;
    private long lastReloadAtElapsedMs = -1;
    private int lastRebufferCount;
    private int evaluations;
    private int reloadAttempts;
    private int successfulReloads;
    private boolean applyInProgress;
    private boolean lastReloadSafety;
    private boolean lastReloadFailed;
    private IjkBufferPolicy.Config lastReloadTarget;
    private IjkBufferPolicy.Config stagedConfig = IjkBufferPolicy.safeInitialConfig();

    public synchronized boolean beginSession(
            PlaybackAutoContext.SessionToken session,
            long startedAtElapsedMs) {
        if (session == null || !session.active()) return false;
        this.session = session;
        this.startedAtElapsedMs = Math.max(0, startedAtElapsedMs);
        lastReloadAtElapsedMs = -1;
        lastRebufferCount = 0;
        evaluations = 0;
        reloadAttempts = 0;
        successfulReloads = 0;
        applyInProgress = false;
        lastReloadSafety = false;
        lastReloadFailed = false;
        lastReloadTarget = null;
        stagedConfig = IjkBufferPolicy.safeInitialConfig();
        return true;
    }

    public synchronized boolean endSession(
            PlaybackAutoContext.SessionToken session) {
        if (!isCurrent(session)) return false;
        this.session = PlaybackAutoContext.SessionToken.none();
        startedAtElapsedMs = 0;
        lastReloadAtElapsedMs = -1;
        lastRebufferCount = 0;
        applyInProgress = false;
        lastReloadSafety = false;
        lastReloadFailed = false;
        lastReloadTarget = null;
        stagedConfig = IjkBufferPolicy.safeInitialConfig();
        return true;
    }

    public synchronized Decision stageInitial(
            PlaybackAutoContext.SessionToken session,
            PlaybackAutoContext.SessionToken factsSession,
            IjkBufferPolicy.Decision policy) {
        if (!isCurrent(session) || !session.equals(factsSession)) {
            return Decision.hold(IjkBufferPolicy.safeInitialConfig(),
                    IjkBufferPolicy.safeInitialConfig(), policy,
                    Reason.STALE_SESSION, 0, false);
        }
        IjkBufferPolicy.Decision safe = policy == null
                ? IjkBufferPolicy.resolve(null) : policy;
        stagedConfig = safe.managed()
                ? safe.target() : IjkBufferPolicy.safeInitialConfig();
        return new Decision(Action.STAGE, stagedConfig, stagedConfig, safe,
                safe.managed() ? Reason.INITIAL_STAGE : Reason.NOT_MANAGED,
                0, false);
    }

    public synchronized Decision evaluate(
            PlaybackAutoContext.SessionToken session,
            PlaybackAutoContext.SessionToken factsSession,
            IjkBufferPolicy.Decision policy,
            IjkBufferPolicy.Config appliedConfig,
            Trigger trigger,
            boolean buffering,
            boolean firstFrameRendered,
            int observedRebufferCount,
            long nowElapsedMs) {
        if (!isCurrent(session) || !session.equals(factsSession)) {
            return Decision.hold(appliedConfig, appliedConfig, policy,
                    Reason.STALE_SESSION, 0, false);
        }
        evaluations++;
        long now = Math.max(0, nowElapsedMs);
        IjkBufferPolicy.Decision safe = policy == null
                ? IjkBufferPolicy.resolve(null) : policy;
        IjkBufferPolicy.Config current = appliedConfig == null
                ? stagedConfig : appliedConfig;
        int rebufferCount = Math.max(0, observedRebufferCount);
        boolean newRebuffer = rebufferCount > lastRebufferCount;
        lastRebufferCount = Math.max(lastRebufferCount, rebufferCount);

        if (!safe.managed()) {
            return Decision.hold(current, current, safe, Reason.NOT_MANAGED,
                    0, newRebuffer);
        }
        IjkBufferPolicy.Config target = safe.target();
        if (target.equals(current)) {
            stagedConfig = target;
            return Decision.hold(current, target, safe, Reason.ALREADY_APPLIED,
                    0, newRebuffer);
        }
        if (applyInProgress) {
            return Decision.hold(current, target, safe, Reason.APPLY_IN_PROGRESS,
                    0, newRebuffer);
        }

        boolean shrinks = shrinks(current, target);
        boolean expands = expands(current, target);
        boolean hardSafety = safe.liveLagHigh()
                || (hardSafety(safe.reason()) && shrinks);
        boolean earlySceneShrink = trigger == Trigger.MANIFEST
                && shrinks
                && now - startedAtElapsedMs <= EARLY_SCENE_WINDOW_MS;
        boolean rebufferExpansion = newRebuffer && expands && buffering;
        boolean reload = hardSafety || earlySceneShrink || rebufferExpansion;
        if (!reload) {
            stagedConfig = target;
            Reason reason = expands
                    ? firstFrameRendered ? Reason.HEALTHY_EXPANSION_DEFERRED
                    : Reason.STARTUP_EXPANSION_DEFERRED
                    : Reason.NONCRITICAL_CHANGE_DEFERRED;
            return new Decision(Action.STAGE, current, target, safe, reason,
                    cooldownRemaining(now), newRebuffer);
        }
        if (reloadAttempts >= MAX_RELOAD_ATTEMPTS) {
            stagedConfig = current;
            return Decision.hold(current, target, safe, Reason.RELOAD_LIMIT,
                    cooldownRemaining(now), newRebuffer);
        }
        long cooldown = cooldownRemaining(now);
        boolean escalatesFailedSafety = hardSafety
                && lastReloadSafety
                && lastReloadFailed
                && lastReloadTarget != null
                && shrinks(lastReloadTarget, target);
        boolean safetyBypass = hardSafety
                && (!lastReloadSafety || !lastReloadFailed
                || escalatesFailedSafety);
        if (cooldown > 0 && !safetyBypass) {
            if (lastReloadSafety && lastReloadFailed
                    && target.equals(lastReloadTarget)) {
                stagedConfig = current;
            } else {
                stagedConfig = target;
            }
            return Decision.hold(current, target, safe, Reason.RELOAD_COOLDOWN,
                    cooldown, newRebuffer);
        }
        Reason reason = hardSafety ? Reason.SAFETY_RELOAD
                : rebufferExpansion ? Reason.REBUFFER_RELOAD
                : Reason.EARLY_SCENE_RELOAD;
        return new Decision(Action.RELOAD, current, target, safe, reason,
                0, newRebuffer);
    }

    /**
     * Reserves the same bounded reload lane for an RTSP/RTMP live recovery.
     * The queue configuration is intentionally unchanged; reconnecting the
     * media session is the recovery action.
     */
    public synchronized Decision requestRealtimeRecovery(
            PlaybackAutoContext.SessionToken session,
            PlaybackAutoContext.SessionToken factsSession,
            IjkBufferPolicy.Config appliedConfig,
            long nowElapsedMs) {
        return requestSharedReload(
                session,
                factsSession,
                appliedConfig,
                nowElapsedMs,
                Reason.REALTIME_RECOVERY_RELOAD);
    }

    /** Reserves the same bounded reload lane for prepare-time decode tuning. */
    public synchronized Decision requestDecodePressureReload(
            PlaybackAutoContext.SessionToken session,
            PlaybackAutoContext.SessionToken factsSession,
            IjkBufferPolicy.Config appliedConfig,
            long nowElapsedMs) {
        return requestSharedReload(
                session,
                factsSession,
                appliedConfig,
                nowElapsedMs,
                Reason.DECODE_PRESSURE_RELOAD);
    }

    private Decision requestSharedReload(
            PlaybackAutoContext.SessionToken session,
            PlaybackAutoContext.SessionToken factsSession,
            IjkBufferPolicy.Config appliedConfig,
            long nowElapsedMs,
            Reason reloadReason) {
        IjkBufferPolicy.Config current = appliedConfig == null
                ? stagedConfig : appliedConfig;
        IjkBufferPolicy.Decision policy = currentPolicy(current);
        if (!isCurrent(session) || !session.equals(factsSession)) {
            return Decision.hold(current, current, policy,
                    Reason.STALE_SESSION, 0, false);
        }
        evaluations++;
        if (applyInProgress) {
            return Decision.hold(current, current, policy,
                    Reason.APPLY_IN_PROGRESS, 0, false);
        }
        if (reloadAttempts >= MAX_RELOAD_ATTEMPTS) {
            return Decision.hold(current, current, policy,
                    Reason.RELOAD_LIMIT, cooldownRemaining(nowElapsedMs),
                    false);
        }
        long cooldown = cooldownRemaining(Math.max(0, nowElapsedMs));
        if (cooldown > 0) {
            return Decision.hold(current, current, policy,
                    Reason.RELOAD_COOLDOWN, cooldown, false);
        }
        stagedConfig = current;
        return new Decision(Action.RELOAD, current, current, policy,
                reloadReason, 0, false);
    }

    public synchronized boolean beginApply(
            PlaybackAutoContext.SessionToken session,
            Decision decision) {
        if (!isCurrent(session) || decision == null
                || decision.action() != Action.RELOAD || applyInProgress) return false;
        applyInProgress = true;
        reloadAttempts++;
        stagedConfig = decision.targetConfig();
        return true;
    }

    public synchronized Decision deferExperimentalReload(
            PlaybackAutoContext.SessionToken session,
            Decision decision) {
        if (!isCurrent(session)
                || decision == null
                || !decision.requestsReload()
                || applyInProgress) {
            IjkBufferPolicy.Config current = decision == null
                    ? stagedConfig : decision.appliedConfig();
            return Decision.hold(current, stagedConfig,
                    decision == null ? currentPolicy(current) : decision.policy(),
                    Reason.EXPERIMENT_DISABLED, 0, false);
        }
        stagedConfig = decision.targetConfig();
        return new Decision(
                Action.STAGE,
                decision.appliedConfig(),
                decision.targetConfig(),
                decision.policy(),
                Reason.EXPERIMENT_DISABLED,
                decision.cooldownRemainingMs(),
                decision.newRebuffer());
    }

    public synchronized void completeApply(
            PlaybackAutoContext.SessionToken session,
            Decision decision,
            boolean succeeded,
            long nowElapsedMs) {
        if (!isCurrent(session) || decision == null || !applyInProgress) return;
        applyInProgress = false;
        lastReloadAtElapsedMs = Math.max(0, nowElapsedMs);
        lastReloadSafety = decision.reason() == Reason.SAFETY_RELOAD;
        lastReloadFailed = !succeeded;
        lastReloadTarget = decision.targetConfig();
        if (!succeeded) {
            stagedConfig = decision.appliedConfig();
            return;
        }
        successfulReloads++;
        stagedConfig = decision.targetConfig();
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(session, stagedConfig, evaluations, reloadAttempts,
                successfulReloads, lastRebufferCount, lastReloadAtElapsedMs,
                applyInProgress);
    }

    private boolean isCurrent(PlaybackAutoContext.SessionToken session) {
        return session != null && session.active() && session.equals(this.session);
    }

    private long cooldownRemaining(long nowElapsedMs) {
        if (lastReloadAtElapsedMs < 0) return 0;
        long elapsed = Math.max(0, nowElapsedMs - lastReloadAtElapsedMs);
        return Math.max(0, RELOAD_COOLDOWN_MS - elapsed);
    }

    private static boolean shrinks(
            IjkBufferPolicy.Config current,
            IjkBufferPolicy.Config target) {
        return target.bufferMb() < current.bufferMb()
                || target.firstWaterMs() < current.firstWaterMs()
                || target.nextWaterMs() < current.nextWaterMs()
                || target.lastWaterMs() < current.lastWaterMs();
    }

    private static boolean expands(
            IjkBufferPolicy.Config current,
            IjkBufferPolicy.Config target) {
        return target.bufferMb() > current.bufferMb()
                || target.firstWaterMs() > current.firstWaterMs()
                || target.nextWaterMs() > current.nextWaterMs()
                || target.lastWaterMs() > current.lastWaterMs();
    }

    private static boolean hardSafety(IjkBufferPolicy.Reason reason) {
        return switch (reason) {
            case CRITICAL_MEMORY,
                 SYSTEM_LOW_MEMORY,
                 LOW_RAM_DEVICE,
                 SMALL_HEAP,
                 LOW_JAVA_HEADROOM,
                 LOW_SYSTEM_SURPLUS,
                 MODERATE_MEMORY,
                 LIVE_LAG_HIGH -> true;
            default -> false;
        };
    }

    private static IjkBufferPolicy.Decision currentPolicy(
            IjkBufferPolicy.Config current) {
        IjkBufferPolicy.Config safe = current == null
                ? IjkBufferPolicy.safeInitialConfig() : current;
        return new IjkBufferPolicy.Decision(
                true,
                safe,
                IjkBufferPolicy.Reason.REALTIME_BASELINE,
                safe.bufferMb(),
                false,
                -1,
                0);
    }

    public enum Trigger {
        INITIAL,
        MANIFEST,
        MEMORY,
        RUNTIME
    }

    public enum Action {
        HOLD,
        STAGE,
        RELOAD
    }

    public enum Reason {
        STALE_SESSION("stale-session"),
        NOT_MANAGED("not-managed"),
        INITIAL_STAGE("initial-stage"),
        ALREADY_APPLIED("already-applied"),
        APPLY_IN_PROGRESS("apply-in-progress"),
        SAFETY_RELOAD("safety-reload"),
        EARLY_SCENE_RELOAD("early-scene-reload"),
        REBUFFER_RELOAD("rebuffer-reload"),
        REALTIME_RECOVERY_RELOAD("realtime-recovery-reload"),
        DECODE_PRESSURE_RELOAD("decode-pressure-reload"),
        EXPERIMENT_DISABLED("experiment-disabled"),
        STARTUP_EXPANSION_DEFERRED("startup-expansion-deferred"),
        HEALTHY_EXPANSION_DEFERRED("healthy-expansion-deferred"),
        NONCRITICAL_CHANGE_DEFERRED("noncritical-change-deferred"),
        RELOAD_COOLDOWN("reload-cooldown"),
        RELOAD_LIMIT("reload-limit");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Decision(
            Action action,
            IjkBufferPolicy.Config appliedConfig,
            IjkBufferPolicy.Config targetConfig,
            IjkBufferPolicy.Decision policy,
            Reason reason,
            long cooldownRemainingMs,
            boolean newRebuffer) {

        public Decision {
            action = action == null ? Action.HOLD : action;
            appliedConfig = appliedConfig == null
                    ? IjkBufferPolicy.safeInitialConfig() : appliedConfig;
            targetConfig = targetConfig == null ? appliedConfig : targetConfig;
            policy = policy == null ? IjkBufferPolicy.resolve(null) : policy;
            reason = reason == null ? Reason.NOT_MANAGED : reason;
            cooldownRemainingMs = Math.max(0, cooldownRemainingMs);
        }

        static Decision hold(
                IjkBufferPolicy.Config applied,
                IjkBufferPolicy.Config target,
                IjkBufferPolicy.Decision policy,
                Reason reason,
                long cooldown,
                boolean newRebuffer) {
            return new Decision(Action.HOLD, applied, target, policy, reason,
                    cooldown, newRebuffer);
        }

        public boolean requestsReload() {
            return action == Action.RELOAD;
        }
    }

    public record Snapshot(
            PlaybackAutoContext.SessionToken session,
            IjkBufferPolicy.Config stagedConfig,
            int evaluations,
            int reloadAttempts,
            int successfulReloads,
            int lastRebufferCount,
            long lastReloadAtElapsedMs,
            boolean applyInProgress) {
    }
}
