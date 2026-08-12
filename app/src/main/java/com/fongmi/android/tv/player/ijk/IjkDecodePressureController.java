package com.fongmi.android.tv.player.ijk;

import com.fongmi.android.tv.player.PlaybackAutoContext;

/** Session-isolated confirmation and staging controller for IJK software-decode pressure. */
public final class IjkDecodePressureController {

    public static final long MIN_SAMPLE_INTERVAL_MS = 4_000L;
    public static final long MAX_CONSECUTIVE_SAMPLE_GAP_MS = 15_000L;
    public static final long USER_SEEK_SUPPRESSION_MS = 15_000L;

    private PlaybackAutoContext.SessionToken session =
            PlaybackAutoContext.SessionToken.none();
    private IjkDecodePressurePolicy.Config stagedConfig =
            IjkDecodePressurePolicy.automaticInitialConfig();
    private Decision pendingDecision;
    private IjkDecodePressurePolicy.Pressure riskPressure =
            IjkDecodePressurePolicy.Pressure.UNKNOWN;
    private int consecutiveRiskSamples;
    private int consecutiveRecoverySamples;
    private int actionAttempts;
    private int successfulActions;
    private int failedActions;
    private long riskSinceMs = -1;
    private long recoverySinceMs = -1;
    private long lastRiskSampleAtMs = -1;
    private long lastRecoverySampleAtMs = -1;
    private long lastEvaluatedAtMs = -1;
    private long userSeekSuppressedUntilMs = -1;
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

    public synchronized Decision stageInitial(
            PlaybackAutoContext.SessionToken token,
            PlaybackAutoContext.SessionToken factsSession,
            boolean automatic) {
        if (!isCurrent(token) || token == null || !token.equals(factsSession)) {
            return Decision.hold(
                    stagedConfig,
                    stagedConfig,
                    IjkDecodePressurePolicy.Assessment.hold(
                            IjkDecodePressurePolicy.Reason.NOT_AUTOMATIC_IJK),
                    Reason.STALE_SESSION);
        }
        stagedConfig = IjkDecodePressurePolicy.automaticInitialConfig();
        return new Decision(
                automatic ? Action.STAGE : Action.HOLD,
                stagedConfig,
                stagedConfig,
                IjkDecodePressurePolicy.Assessment.hold(
                        automatic
                                ? IjkDecodePressurePolicy.Reason.DECODER_UNKNOWN
                                : IjkDecodePressurePolicy.Reason.NOT_AUTOMATIC_IJK),
                automatic ? Reason.INITIAL_STAGE : Reason.NOT_MANAGED);
    }

    public synchronized Decision evaluate(Input input) {
        Input current = input == null ? Input.inactive() : input;
        if (!isCurrent(current.session())
                || !current.session().equals(current.factsSession())) {
            return Decision.hold(
                    current.appliedConfig(),
                    stagedConfig,
                    IjkDecodePressurePolicy.Assessment.hold(
                            IjkDecodePressurePolicy.Reason.NOT_AUTOMATIC_IJK),
                    Reason.STALE_SESSION);
        }
        if (lastEvaluatedAtMs >= 0 && current.nowMs() < lastEvaluatedAtMs) {
            return Decision.hold(
                    current.appliedConfig(),
                    stagedConfig,
                    IjkDecodePressurePolicy.Assessment.hold(
                            IjkDecodePressurePolicy.Reason.FPS_UNKNOWN),
                    Reason.STALE_SAMPLE);
        }
        lastEvaluatedAtMs = current.nowMs();
        boolean userSeeking = current.policyInput().userSeeking()
                || current.nowMs() < userSeekSuppressedUntilMs;
        boolean anyActionPending = actionPending
                || current.reloadActionPending();
        IjkDecodePressurePolicy.Assessment assessment =
                IjkDecodePressurePolicy.assess(
                        current.policyInput().withRuntimeGuards(
                                userSeeking, anyActionPending));

        if (!current.policyInput().automatic()
                || !current.policyInput().ijk()) {
            clearObservations();
            return Decision.hold(
                    current.appliedConfig(), stagedConfig, assessment,
                    Reason.NOT_MANAGED);
        }
        if (assessment.reason()
                == IjkDecodePressurePolicy.Reason.HARDWARE_DECODER) {
            clearObservations();
            IjkDecodePressurePolicy.Config off =
                    IjkDecodePressurePolicy.automaticInitialConfig();
            if (!off.equals(stagedConfig)) {
                stagedConfig = off;
                return new Decision(
                        Action.STAGE,
                        current.appliedConfig(),
                        stagedConfig,
                        assessment,
                        Reason.HARDWARE_STAGED_OFF);
            }
            return Decision.hold(
                    current.appliedConfig(), stagedConfig, assessment,
                    Reason.HEALTHY);
        }
        if (anyActionPending) {
            clearObservations();
            return Decision.hold(
                    current.appliedConfig(), stagedConfig, assessment,
                    Reason.ACTION_PENDING);
        }
        if (assessment.actionableRisk()) {
            return evaluateRisk(current, assessment);
        }
        if (assessment.recoveryEligible()) {
            return evaluateRecovery(current, assessment);
        }
        clearObservations();
        return Decision.hold(
                current.appliedConfig(), stagedConfig, assessment,
                eligibleHoldReason(assessment.reason()));
    }

    public synchronized void onUserSeek(
            PlaybackAutoContext.SessionToken token,
            long nowMs) {
        if (!isCurrent(token)) return;
        userSeekSuppressedUntilMs = saturatingAdd(
                Math.max(0, nowMs), USER_SEEK_SUPPRESSION_MS);
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

    public synchronized boolean beginAction(
            PlaybackAutoContext.SessionToken token,
            Decision decision) {
        if (!isCurrent(token) || decision == null
                || !decision.requestsReload() || actionPending) return false;
        actionPending = true;
        pendingDecision = decision;
        stagedConfig = decision.targetConfig();
        if (actionAttempts < Integer.MAX_VALUE) actionAttempts++;
        clearObservations();
        return true;
    }

    public synchronized void completeAction(
            PlaybackAutoContext.SessionToken token,
            boolean succeeded) {
        if (!isCurrent(token) || !actionPending) return;
        Decision pending = pendingDecision;
        actionPending = false;
        pendingDecision = null;
        if (succeeded) {
            if (successfulActions < Integer.MAX_VALUE) successfulActions++;
        } else {
            if (failedActions < Integer.MAX_VALUE) failedActions++;
            if (pending != null) stagedConfig = pending.appliedConfig();
        }
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                session,
                stagedConfig,
                riskPressure,
                consecutiveRiskSamples,
                consecutiveRecoverySamples,
                actionAttempts,
                successfulActions,
                failedActions,
                riskSinceMs,
                recoverySinceMs,
                lastEvaluatedAtMs,
                userSeekSuppressedUntilMs,
                actionPending);
    }

    private Decision evaluateRisk(
            Input current,
            IjkDecodePressurePolicy.Assessment assessment) {
        clearRecovery();
        IjkDecodePressurePolicy.Config applied = current.appliedConfig();
        if (stagedConfig.tuneMode().ordinal()
                < applied.tuneMode().ordinal()) {
            stagedConfig = applied;
        }
        IjkDecodePressurePolicy.TuneMode targetTune = nextTune(
                applied.tuneMode(), assessment.suggestedTune());
        if (targetTune == applied.tuneMode()) {
            clearRisk();
            stagedConfig = applied;
            return Decision.hold(
                    applied, stagedConfig, assessment,
                    Reason.ALREADY_AT_TARGET);
        }
        if (!sampleRisk(current.nowMs(), assessment.pressure())) {
            return Decision.hold(
                    applied, stagedConfig, assessment,
                    Reason.SAMPLE_TOO_SOON);
        }
        boolean confirmed = consecutiveRiskSamples
                >= IjkDecodePressurePolicy.RISK_CONFIRMATION_SAMPLES
                && riskSinceMs >= 0
                && current.nowMs() - riskSinceMs
                >= IjkDecodePressurePolicy.RISK_CONFIRMATION_WINDOW_MS;
        if (!confirmed) {
            return Decision.hold(
                    applied, stagedConfig, assessment,
                    Reason.CONFIRMING_PRESSURE);
        }
        return new Decision(
                Action.RELOAD,
                applied,
                IjkDecodePressurePolicy.Config.automatic(targetTune),
                assessment,
                Reason.PRESSURE_RELOAD);
    }

    private Decision evaluateRecovery(
            Input current,
            IjkDecodePressurePolicy.Assessment assessment) {
        clearRisk();
        IjkDecodePressurePolicy.Config applied = current.appliedConfig();
        if (applied.tuneMode() == IjkDecodePressurePolicy.TuneMode.OFF) {
            stagedConfig = IjkDecodePressurePolicy.automaticInitialConfig();
            clearRecovery();
            return Decision.hold(
                    applied, stagedConfig, assessment, Reason.HEALTHY);
        }
        if (!sampleRecovery(current.nowMs())) {
            return Decision.hold(
                    applied, stagedConfig, assessment,
                    Reason.SAMPLE_TOO_SOON);
        }
        boolean confirmed = consecutiveRecoverySamples
                >= IjkDecodePressurePolicy.RECOVERY_CONFIRMATION_SAMPLES
                && recoverySinceMs >= 0
                && current.nowMs() - recoverySinceMs
                >= IjkDecodePressurePolicy.RECOVERY_CONFIRMATION_WINDOW_MS;
        if (!confirmed) {
            return Decision.hold(
                    applied, stagedConfig, assessment,
                    Reason.RECOVERY_CONFIRMING);
        }
        IjkDecodePressurePolicy.TuneMode base =
                stagedConfig.tuneMode().ordinal()
                < applied.tuneMode().ordinal()
                        ? stagedConfig.tuneMode() : applied.tuneMode();
        stagedConfig = IjkDecodePressurePolicy.Config.automatic(
                lowerTune(base));
        clearRecovery();
        return new Decision(
                Action.STAGE,
                applied,
                stagedConfig,
                assessment,
                Reason.RECOVERY_STAGED);
    }

    private boolean sampleRisk(
            long nowMs,
            IjkDecodePressurePolicy.Pressure pressure) {
        if (lastRiskSampleAtMs >= 0
                && nowMs - lastRiskSampleAtMs < MIN_SAMPLE_INTERVAL_MS) {
            return false;
        }
        if (lastRiskSampleAtMs >= 0
                && nowMs - lastRiskSampleAtMs
                > MAX_CONSECUTIVE_SAMPLE_GAP_MS) {
            clearRisk();
        }
        if (riskPressure != pressure) clearRisk();
        if (consecutiveRiskSamples == 0) riskSinceMs = nowMs;
        if (consecutiveRiskSamples < Integer.MAX_VALUE) {
            consecutiveRiskSamples++;
        }
        riskPressure = pressure;
        lastRiskSampleAtMs = nowMs;
        return true;
    }

    private boolean sampleRecovery(long nowMs) {
        if (lastRecoverySampleAtMs >= 0
                && nowMs - lastRecoverySampleAtMs
                < MIN_SAMPLE_INTERVAL_MS) return false;
        if (lastRecoverySampleAtMs >= 0
                && nowMs - lastRecoverySampleAtMs
                > MAX_CONSECUTIVE_SAMPLE_GAP_MS) {
            clearRecovery();
        }
        if (consecutiveRecoverySamples == 0) recoverySinceMs = nowMs;
        if (consecutiveRecoverySamples < Integer.MAX_VALUE) {
            consecutiveRecoverySamples++;
        }
        lastRecoverySampleAtMs = nowMs;
        return true;
    }

    private static IjkDecodePressurePolicy.TuneMode nextTune(
            IjkDecodePressurePolicy.TuneMode applied,
            IjkDecodePressurePolicy.TuneMode suggested) {
        IjkDecodePressurePolicy.TuneMode current = applied == null
                ? IjkDecodePressurePolicy.TuneMode.OFF : applied;
        IjkDecodePressurePolicy.TuneMode target = suggested == null
                ? IjkDecodePressurePolicy.TuneMode.OFF : suggested;
        if (current == IjkDecodePressurePolicy.TuneMode.OFF
                && target.ordinal()
                >= IjkDecodePressurePolicy.TuneMode.MILD.ordinal()) {
            return IjkDecodePressurePolicy.TuneMode.MILD;
        }
        if (current == IjkDecodePressurePolicy.TuneMode.MILD
                && target == IjkDecodePressurePolicy.TuneMode.AGGRESSIVE) {
            return IjkDecodePressurePolicy.TuneMode.AGGRESSIVE;
        }
        return current;
    }

    private static IjkDecodePressurePolicy.TuneMode lowerTune(
            IjkDecodePressurePolicy.TuneMode tune) {
        return switch (tune == null
                ? IjkDecodePressurePolicy.TuneMode.OFF : tune) {
            case AGGRESSIVE -> IjkDecodePressurePolicy.TuneMode.MILD;
            case MILD, OFF -> IjkDecodePressurePolicy.TuneMode.OFF;
        };
    }

    private static Reason eligibleHoldReason(
            IjkDecodePressurePolicy.Reason reason) {
        return switch (reason) {
            case ACTION_PENDING -> Reason.ACTION_PENDING;
            case NOT_AUTOMATIC_IJK -> Reason.NOT_MANAGED;
            case HEALTHY, THERMAL_NOMINAL_HOLD -> Reason.HEALTHY;
            default -> Reason.INELIGIBLE;
        };
    }

    private boolean isCurrent(PlaybackAutoContext.SessionToken token) {
        return token != null && token.active() && token.equals(session);
    }

    private void clearRisk() {
        riskPressure = IjkDecodePressurePolicy.Pressure.UNKNOWN;
        consecutiveRiskSamples = 0;
        riskSinceMs = -1;
        lastRiskSampleAtMs = -1;
    }

    private void clearRecovery() {
        consecutiveRecoverySamples = 0;
        recoverySinceMs = -1;
        lastRecoverySampleAtMs = -1;
    }

    private void clearObservations() {
        clearRisk();
        clearRecovery();
    }

    private void resetAll(PlaybackAutoContext.SessionToken token) {
        session = token == null
                ? PlaybackAutoContext.SessionToken.none() : token;
        stagedConfig = IjkDecodePressurePolicy.automaticInitialConfig();
        pendingDecision = null;
        clearObservations();
        actionAttempts = 0;
        successfulActions = 0;
        failedActions = 0;
        lastEvaluatedAtMs = -1;
        userSeekSuppressedUntilMs = -1;
        actionPending = false;
    }

    private static long saturatingAdd(long first, long second) {
        long safeFirst = Math.max(0, first);
        long safeSecond = Math.max(0, second);
        return safeFirst > Long.MAX_VALUE - safeSecond
                ? Long.MAX_VALUE : safeFirst + safeSecond;
    }

    public enum Action {
        HOLD("hold"),
        STAGE("stage"),
        RELOAD("reload");

        private final String label;

        Action(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Reason {
        STALE_SESSION("stale-session"),
        STALE_SAMPLE("stale-sample"),
        NOT_MANAGED("not-managed"),
        INITIAL_STAGE("initial-stage"),
        INELIGIBLE("ineligible"),
        ACTION_PENDING("action-pending"),
        HARDWARE_STAGED_OFF("hardware-staged-off"),
        SAMPLE_TOO_SOON("sample-too-soon"),
        CONFIRMING_PRESSURE("confirming-pressure"),
        PRESSURE_RELOAD("pressure-reload"),
        ALREADY_AT_TARGET("already-at-target"),
        RECOVERY_CONFIRMING("recovery-confirming"),
        RECOVERY_STAGED("recovery-staged"),
        HEALTHY("healthy");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Input(
            PlaybackAutoContext.SessionToken session,
            PlaybackAutoContext.SessionToken factsSession,
            IjkDecodePressurePolicy.Input policyInput,
            IjkDecodePressurePolicy.Config appliedConfig,
            boolean reloadActionPending,
            long nowMs) {

        public Input {
            session = session == null
                    ? PlaybackAutoContext.SessionToken.none() : session;
            factsSession = factsSession == null
                    ? PlaybackAutoContext.SessionToken.none() : factsSession;
            policyInput = policyInput == null
                    ? IjkDecodePressurePolicy.Input.inactive() : policyInput;
            appliedConfig = appliedConfig == null
                    ? IjkDecodePressurePolicy.automaticInitialConfig()
                    : appliedConfig;
            nowMs = Math.max(0, nowMs);
        }

        static Input inactive() {
            return new Input(
                    PlaybackAutoContext.SessionToken.none(),
                    PlaybackAutoContext.SessionToken.none(),
                    IjkDecodePressurePolicy.Input.inactive(),
                    IjkDecodePressurePolicy.automaticInitialConfig(),
                    false,
                    0);
        }
    }

    public record Decision(
            Action action,
            IjkDecodePressurePolicy.Config appliedConfig,
            IjkDecodePressurePolicy.Config targetConfig,
            IjkDecodePressurePolicy.Assessment assessment,
            Reason reason) {

        public Decision {
            action = action == null ? Action.HOLD : action;
            appliedConfig = appliedConfig == null
                    ? IjkDecodePressurePolicy.automaticInitialConfig()
                    : appliedConfig;
            targetConfig = targetConfig == null
                    ? appliedConfig : targetConfig;
            assessment = assessment == null
                    ? IjkDecodePressurePolicy.Assessment.hold(
                    IjkDecodePressurePolicy.Reason.FPS_UNKNOWN)
                    : assessment;
            reason = reason == null ? Reason.INELIGIBLE : reason;
        }

        static Decision hold(
                IjkDecodePressurePolicy.Config applied,
                IjkDecodePressurePolicy.Config target,
                IjkDecodePressurePolicy.Assessment assessment,
                Reason reason) {
            return new Decision(
                    Action.HOLD, applied, target, assessment, reason);
        }

        public boolean requestsReload() {
            return action == Action.RELOAD;
        }
    }

    public record Snapshot(
            PlaybackAutoContext.SessionToken session,
            IjkDecodePressurePolicy.Config stagedConfig,
            IjkDecodePressurePolicy.Pressure riskPressure,
            int consecutiveRiskSamples,
            int consecutiveRecoverySamples,
            int actionAttempts,
            int successfulActions,
            int failedActions,
            long riskSinceMs,
            long recoverySinceMs,
            long lastEvaluatedAtMs,
            long userSeekSuppressedUntilMs,
            boolean actionPending) {
    }
}
