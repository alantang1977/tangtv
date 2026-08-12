package com.fongmi.android.tv.player.mpv;

import com.fongmi.android.tv.player.PlaybackAutoContext;

/** Session-isolated hysteresis controller for MPV automatic forward-cache updates. */
public final class MpvForwardCacheController {

    public static final long DEMAND_DECREASE_STABLE_MS = 10_000L;
    public static final int DEMAND_DECREASE_MIN_SAMPLES = 2;
    public static final long EXPANSION_STABLE_MS = 20_000L;
    public static final int EXPANSION_MIN_SAMPLES = 4;
    public static final long EXPANSION_STEP_INTERVAL_MS = 15_000L;
    public static final long RECOVERY_STABLE_MS = 30_000L;
    public static final long MODERATE_COOLDOWN_MS = 30_000L;
    public static final long CRITICAL_COOLDOWN_MS = 60_000L;

    private PlaybackAutoContext.SessionToken session = PlaybackAutoContext.SessionToken.none();
    private State state = State.IDLE;
    private boolean baselineInitialized;
    private long initialBaselineBytes = -1;
    private long controlledTargetBytes = -1;
    private long nativeTargetBytes = -1;
    private long lastSafeTargetBytes = -1;
    private long lastMemorySampleAtElapsedMs = -1;
    private PlaybackAutoContext.MemoryPressure lastObservedPressure =
            PlaybackAutoContext.MemoryPressure.UNKNOWN;
    private RecoveryClass recoveryClass = RecoveryClass.NONE;
    private long moderateTargetBytes = -1;
    private long lastPressureAtElapsedMs = -1;
    private long normalSinceElapsedMs = -1;
    private int normalSamples;
    private long demandCandidateBytes = -1;
    private long demandCandidateSinceElapsedMs = -1;
    private int demandSamples;
    private long lastDemandSampleAtElapsedMs = -1;
    private long lastIncreaseAtElapsedMs = -1;
    private long lastDecreaseAtElapsedMs = -1;
    private boolean resourceRecoveryPending;
    private int evaluations;
    private int applyAttempts;
    private boolean lastApplySucceeded;
    private Decision lastDecision = Decision.hold(
            Trigger.BASELINE, Reason.WAITING_FOR_BASELINE, -1, -1, -1, 0, 0);

    public synchronized void beginSession(PlaybackAutoContext.SessionToken token) {
        reset(token);
    }

    public synchronized void endSession(PlaybackAutoContext.SessionToken token) {
        if (!isCurrent(token)) return;
        reset(PlaybackAutoContext.SessionToken.none());
    }

    /** Records the M-01 baseline accepted by the current native context. */
    public synchronized boolean recordBaseline(
            PlaybackAutoContext.SessionToken token,
            long baselineBytes,
            boolean preserveControlledTarget) {
        if (!isCurrent(token)) return false;
        long baseline = MpvForwardCachePolicy.normalizeTier(baselineBytes);
        if (!baselineInitialized || !preserveControlledTarget) {
            initialBaselineBytes = baseline;
            controlledTargetBytes = baseline;
            clearHysteresis();
        }
        baselineInitialized = true;
        nativeTargetBytes = baseline;
        if (controlledTargetBytes < 0) controlledTargetBytes = baseline;
        state = controlledTargetBytes == nativeTargetBytes
                ? State.ACTIVE : State.CONTEXT_RESTORE_PENDING;
        lastApplySucceeded = true;
        return true;
    }

    public synchronized void suppress(PlaybackAutoContext.SessionToken token) {
        if (!isCurrent(token)) return;
        baselineInitialized = false;
        initialBaselineBytes = -1;
        controlledTargetBytes = -1;
        nativeTargetBytes = -1;
        clearHysteresis();
        state = State.SUPPRESSED;
    }

    /** Synchronizes the forward half after a combined forward/back native update. */
    public synchronized boolean syncNativeTarget(
            PlaybackAutoContext.SessionToken token,
            long targetBytes) {
        if (!isCurrent(token) || !baselineInitialized) return false;
        nativeTargetBytes = MpvForwardCachePolicy.normalizeTier(targetBytes);
        if (state == State.CONTEXT_RESTORE_PENDING
                && nativeTargetBytes == controlledTargetBytes) {
            state = State.ACTIVE;
        }
        return true;
    }

    public synchronized Decision evaluate(
            PlaybackAutoContext.SessionToken token,
            PlaybackAutoContext.SessionToken factsSession,
            MpvForwardCachePolicy.Assessment assessment,
            Trigger trigger,
            long nowElapsedMs) {
        return evaluate(token, factsSession, assessment,
                MpvResourcePressureController.Decision.unrestricted(),
                trigger, nowElapsedMs);
    }

    public synchronized Decision evaluate(
            PlaybackAutoContext.SessionToken token,
            PlaybackAutoContext.SessionToken factsSession,
            MpvForwardCachePolicy.Assessment assessment,
            MpvResourcePressureController.Decision resourceDecision,
            Trigger trigger,
            long nowElapsedMs) {
        Trigger source = trigger == null ? Trigger.RUNTIME : trigger;
        long now = Math.max(0, nowElapsedMs);
        if (!isCurrent(token) || factsSession == null || !token.equals(factsSession)) {
            return remember(Decision.hold(source, Reason.STALE_SESSION,
                    nativeTargetBytes, controlledTargetBytes, -1, normalSamples,
                    cooldownRemainingMs(now)));
        }
        if (state == State.APPLYING) {
            return remember(Decision.hold(source, Reason.APPLY_PENDING,
                    nativeTargetBytes, controlledTargetBytes, -1, normalSamples,
                    cooldownRemainingMs(now)));
        }
        MpvForwardCachePolicy.Assessment current = assessment == null
                ? MpvForwardCachePolicy.Assessment.inactive(
                initialBaselineBytes, MpvForwardCachePolicy.Reason.NOT_AUTOMATIC_MPV)
                : assessment;
        MpvResourcePressureController.Decision resource = resourceDecision == null
                ? MpvResourcePressureController.Decision.unrestricted()
                : resourceDecision;
        if (!current.active()) {
            state = current.inactiveReason() == MpvForwardCachePolicy.Reason.CONFIG_PRIORITY
                    ? State.SUPPRESSED : State.INACTIVE;
            return remember(Decision.hold(source,
                    current.inactiveReason() == MpvForwardCachePolicy.Reason.CONFIG_PRIORITY
                            ? Reason.CONFIG_PRIORITY : Reason.NOT_AUTOMATIC_MPV,
                    nativeTargetBytes, controlledTargetBytes,
                    current.safeTargetBytes(), normalSamples, cooldownRemainingMs(now)));
        }
        if (!baselineInitialized) {
            state = State.WAITING_FOR_BASELINE;
            return remember(Decision.hold(source, Reason.WAITING_FOR_BASELINE,
                    nativeTargetBytes, controlledTargetBytes,
                    current.safeTargetBytes(), normalSamples, cooldownRemainingMs(now)));
        }
        if (evaluations < Integer.MAX_VALUE) evaluations++;

        observeDemand(current, source, now);
        long previousSafeTarget = lastSafeTargetBytes;
        observeMemory(current);
        boolean canAdoptNative = source == Trigger.BASELINE || source == Trigger.REBUILD;
        long safeTarget = current.safeTargetBytes();
        long resourceCeiling = resource.active()
                ? resource.forwardCeilingBytes()
                : MpvForwardCachePolicy.MAX_FORWARD_BYTES;
        long effectiveSafeTarget = Math.min(safeTarget, resourceCeiling);

        if (current.critical()) {
            enterPressure(RecoveryClass.CRITICAL,
                    current.memorySampleAtElapsedMs(), now);
            return targetOrAdopt(source, Reason.CRITICAL_PRESSURE,
                    MpvForwardCachePolicy.MIN_FORWARD_BYTES,
                    effectiveSafeTarget, canAdoptNative, now);
        }

        if (effectiveSafeTarget < controlledTargetBytes) {
            boolean resourceLimited = resourceCeiling < safeTarget;
            if (resourceLimited) {
                resourceRecoveryPending = true;
            } else {
                enterCapacityReduction(previousSafeTarget, safeTarget, now);
            }
            return targetOrAdopt(source,
                    resourceLimited ? resourceLimitReason(resource)
                            : Reason.CAPACITY_REDUCTION,
                    effectiveSafeTarget,
                    effectiveSafeTarget,
                    canAdoptNative,
                    now);
        }

        if (current.pressureUsable()
                && current.memoryPressure() == PlaybackAutoContext.MemoryPressure.MODERATE) {
            if (moderateTargetBytes < 0) {
                moderateTargetBytes = MpvForwardCachePolicy.previousTier(controlledTargetBytes);
            }
            enterPressure(RecoveryClass.MODERATE,
                    current.memorySampleAtElapsedMs(), now);
            long target = Math.min(safeTarget, moderateTargetBytes);
            return targetOrAdopt(source, Reason.MODERATE_PRESSURE,
                    target, effectiveSafeTarget, canAdoptNative, now);
        }

        long recoveryCeiling = current.mediaReliable()
                ? Math.min(current.mediaTargetBytes(), effectiveSafeTarget)
                : Math.min(initialBaselineBytes, effectiveSafeTarget);
        if (recoveryClass != RecoveryClass.NONE) {
            if (controlledTargetBytes < recoveryCeiling
                    && resource.active()
                    && !resource.expansionAllowed()) {
                state = State.RECOVERY_WAIT;
                return remember(Decision.hold(source,
                        Reason.RESOURCE_EXPANSION_HOLD,
                        nativeTargetBytes,
                        controlledTargetBytes,
                        effectiveSafeTarget,
                        normalSamples,
                        resource.cooldownRemainingMs()));
            }
            if (!current.normalMemoryEvidence()) {
                state = State.RECOVERY_WAIT;
                return remember(Decision.hold(source, Reason.RECOVERY_WAIT,
                        nativeTargetBytes, controlledTargetBytes,
                        effectiveSafeTarget, normalSamples, cooldownRemainingMs(now)));
            }
            boolean stable = normalSamples >= 2
                    && elapsed(now, normalSinceElapsedMs) >= RECOVERY_STABLE_MS;
            boolean cooledDown = cooldownRemainingMs(now) == 0;
            boolean stepReady = lastIncreaseAtElapsedMs < 0
                    || elapsed(now, lastIncreaseAtElapsedMs) >= EXPANSION_STEP_INTERVAL_MS;
            if (controlledTargetBytes < recoveryCeiling) {
                if (!stable || !cooledDown || !stepReady) {
                    state = State.RECOVERY_WAIT;
                    return remember(Decision.hold(source, Reason.RECOVERY_WAIT,
                            nativeTargetBytes, controlledTargetBytes,
                            effectiveSafeTarget, normalSamples, cooldownRemainingMs(now)));
                }
                long target = MpvForwardCachePolicy.nextTier(
                        controlledTargetBytes, recoveryCeiling);
                return targetOrAdopt(source, Reason.RECOVERY_STEP,
                        target, effectiveSafeTarget, canAdoptNative, now);
            }
            if (!stable || !cooledDown) {
                state = State.RECOVERY_WAIT;
                return remember(Decision.hold(source, Reason.RECOVERY_WAIT,
                        nativeTargetBytes, controlledTargetBytes,
                        effectiveSafeTarget, normalSamples, cooldownRemainingMs(now)));
            }
            recoveryClass = RecoveryClass.NONE;
            moderateTargetBytes = -1;
            lastPressureAtElapsedMs = -1;
            normalSinceElapsedMs = -1;
            normalSamples = 0;
        }

        if (resourceRecoveryPending) {
            long restoreCeiling = current.mediaReliable()
                    ? Math.min(current.mediaTargetBytes(), effectiveSafeTarget)
                    : Math.min(initialBaselineBytes, effectiveSafeTarget);
            if (controlledTargetBytes < restoreCeiling) {
                if (!resource.expansionAllowed()) {
                    state = State.RECOVERY_WAIT;
                    return remember(Decision.hold(source,
                            Reason.RESOURCE_EXPANSION_HOLD,
                            nativeTargetBytes,
                            controlledTargetBytes,
                            effectiveSafeTarget,
                            normalSamples,
                            resource.cooldownRemainingMs()));
                }
                return targetOrAdopt(source, Reason.RESOURCE_RECOVERY_STEP,
                        MpvForwardCachePolicy.nextTier(
                                controlledTargetBytes, restoreCeiling),
                        effectiveSafeTarget, false, now);
            }
            resourceRecoveryPending = false;
        }

        if (nativeTargetBytes != controlledTargetBytes) {
            if (resource.active()
                    && !resource.expansionAllowed()
                    && nativeTargetBytes >= 0
                    && nativeTargetBytes < controlledTargetBytes) {
                resourceRecoveryPending = true;
                return targetOrAdopt(source, Reason.RESOURCE_CONTEXT_HOLD,
                        nativeTargetBytes, effectiveSafeTarget, true, now);
            }
            return targetOrAdopt(source, Reason.CONTEXT_RESTORE,
                    controlledTargetBytes, effectiveSafeTarget, false, now);
        }

        if (!current.mediaReliable()) {
            state = State.ACTIVE;
            return remember(Decision.hold(source, Reason.UNKNOWN_MEDIA_HOLD,
                    nativeTargetBytes, controlledTargetBytes,
                    effectiveSafeTarget, normalSamples, cooldownRemainingMs(now)));
        }

        long unconstrainedMediaCeiling = Math.min(
                current.mediaTargetBytes(), safeTarget);
        long mediaCeiling = Math.min(
                unconstrainedMediaCeiling, resourceCeiling);
        if (unconstrainedMediaCeiling > controlledTargetBytes
                && mediaCeiling <= controlledTargetBytes
                && resource.active()
                && !resource.expansionAllowed()) {
            state = State.DEMAND_WAIT;
            return remember(Decision.hold(source,
                    Reason.RESOURCE_EXPANSION_HOLD,
                    nativeTargetBytes,
                    controlledTargetBytes,
                    effectiveSafeTarget,
                    normalSamples,
                    resource.cooldownRemainingMs()));
        }
        if (mediaCeiling < controlledTargetBytes) {
            boolean stable = demandSamples >= DEMAND_DECREASE_MIN_SAMPLES
                    && elapsed(now, demandCandidateSinceElapsedMs)
                    >= DEMAND_DECREASE_STABLE_MS;
            boolean stepReady = lastDecreaseAtElapsedMs < 0
                    || elapsed(now, lastDecreaseAtElapsedMs)
                    >= DEMAND_DECREASE_STABLE_MS;
            if (!stable || !stepReady) {
                state = State.DEMAND_WAIT;
                return remember(Decision.hold(source, Reason.DEMAND_DECREASE_WAIT,
                        nativeTargetBytes, controlledTargetBytes,
                        effectiveSafeTarget, normalSamples, cooldownRemainingMs(now)));
            }
            long target = Math.max(mediaCeiling,
                    MpvForwardCachePolicy.previousTier(controlledTargetBytes));
            return targetOrAdopt(source, Reason.DEMAND_DECREASE_STEP,
                    target, effectiveSafeTarget, false, now);
        }

        if (mediaCeiling > controlledTargetBytes) {
            if (resource.active() && !resource.expansionAllowed()) {
                state = State.DEMAND_WAIT;
                return remember(Decision.hold(source,
                        Reason.RESOURCE_EXPANSION_HOLD,
                        nativeTargetBytes,
                        controlledTargetBytes,
                        effectiveSafeTarget,
                        normalSamples,
                        resource.cooldownRemainingMs()));
            }
            boolean stable = demandSamples >= EXPANSION_MIN_SAMPLES
                    && elapsed(now, demandCandidateSinceElapsedMs) >= EXPANSION_STABLE_MS;
            boolean stepReady = lastIncreaseAtElapsedMs < 0
                    || elapsed(now, lastIncreaseAtElapsedMs)
                    >= EXPANSION_STEP_INTERVAL_MS;
            if (!current.normalMemoryEvidence() || !stable || !stepReady) {
                state = State.DEMAND_WAIT;
                return remember(Decision.hold(source, Reason.DEMAND_INCREASE_WAIT,
                        nativeTargetBytes, controlledTargetBytes,
                        effectiveSafeTarget, normalSamples, cooldownRemainingMs(now)));
            }
            long target = MpvForwardCachePolicy.nextTier(
                    controlledTargetBytes, mediaCeiling);
            return targetOrAdopt(source, Reason.DEMAND_INCREASE_STEP,
                    target, effectiveSafeTarget, false, now);
        }

        state = State.ACTIVE;
        return remember(Decision.hold(source, Reason.TARGET_STABLE,
                nativeTargetBytes, controlledTargetBytes,
                effectiveSafeTarget, normalSamples, cooldownRemainingMs(now)));
    }

    /** Commits an action before calling MPV so synchronous callbacks cannot duplicate it. */
    public synchronized boolean beginApply(
            PlaybackAutoContext.SessionToken token,
            Decision decision) {
        if (!isCurrent(token)
                || decision == null
                || !decision.requestsApply()
                || !decision.equals(lastDecision)
                || state != State.APPLY_READY) {
            return false;
        }
        state = State.APPLYING;
        if (applyAttempts < Integer.MAX_VALUE) applyAttempts++;
        lastApplySucceeded = false;
        return true;
    }

    public synchronized void completeApply(
            PlaybackAutoContext.SessionToken token,
            Decision decision,
            boolean accepted,
            boolean staged,
            long completedAtElapsedMs) {
        if (!isCurrent(token)
                || decision == null
                || !decision.equals(lastDecision)
                || state != State.APPLYING) {
            return;
        }
        long completedAt = Math.max(0, completedAtElapsedMs);
        lastApplySucceeded = accepted;
        if (!accepted) {
            state = State.FAILED;
            return;
        }
        long previousControlled = controlledTargetBytes;
        controlledTargetBytes = decision.targetBytes();
        nativeTargetBytes = decision.targetBytes();
        if (controlledTargetBytes > previousControlled) {
            lastIncreaseAtElapsedMs = completedAt;
        } else if (controlledTargetBytes < previousControlled) {
            lastDecreaseAtElapsedMs = completedAt;
        }
        state = staged ? State.STAGED : State.APPLIED;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                session,
                state,
                baselineInitialized,
                initialBaselineBytes,
                controlledTargetBytes,
                nativeTargetBytes,
                lastSafeTargetBytes,
                recoveryClass,
                lastObservedPressure,
                lastPressureAtElapsedMs,
                normalSinceElapsedMs,
                normalSamples,
                demandCandidateBytes,
                demandCandidateSinceElapsedMs,
                demandSamples,
                lastIncreaseAtElapsedMs,
                lastDecreaseAtElapsedMs,
                resourceRecoveryPending,
                evaluations,
                applyAttempts,
                lastApplySucceeded,
                lastDecision);
    }

    private Decision targetOrAdopt(
            Trigger trigger,
            Reason reason,
            long requestedTargetBytes,
            long safeTargetBytes,
            boolean canAdoptNative,
            long nowElapsedMs) {
        long target = MpvForwardCachePolicy.normalizeTier(
                Math.min(requestedTargetBytes, safeTargetBytes));
        if (target == controlledTargetBytes && target == nativeTargetBytes) {
            state = holdState(reason);
            return remember(Decision.hold(trigger, reason,
                    nativeTargetBytes, controlledTargetBytes,
                    safeTargetBytes, normalSamples, cooldownRemainingMs(nowElapsedMs)));
        }
        if (canAdoptNative && target == nativeTargetBytes) {
            controlledTargetBytes = target;
            state = holdState(reason);
            return remember(Decision.hold(trigger, reason,
                    nativeTargetBytes, controlledTargetBytes,
                    safeTargetBytes, normalSamples, cooldownRemainingMs(nowElapsedMs)));
        }
        state = State.APPLY_READY;
        return remember(new Decision(
                Action.APPLY,
                trigger,
                reason,
                nativeTargetBytes,
                controlledTargetBytes,
                target,
                safeTargetBytes,
                normalSamples,
                cooldownRemainingMs(nowElapsedMs)));
    }

    private static State holdState(Reason reason) {
        if (reason == Reason.CRITICAL_PRESSURE
                || reason == Reason.MODERATE_PRESSURE
                || reason == Reason.CAPACITY_REDUCTION
                || reason == Reason.RESOURCE_HARD_PRESSURE
                || reason == Reason.RESOURCE_LIMIT
                || reason == Reason.RESOURCE_EXPANSION_HOLD
                || reason == Reason.RESOURCE_RECOVERY_STEP
                || reason == Reason.RESOURCE_CONTEXT_HOLD
                || reason == Reason.RECOVERY_WAIT
                || reason == Reason.RECOVERY_STEP) {
            return State.RECOVERY_WAIT;
        }
        if (reason == Reason.DEMAND_DECREASE_WAIT
                || reason == Reason.DEMAND_INCREASE_WAIT) {
            return State.DEMAND_WAIT;
        }
        return State.ACTIVE;
    }

    private static Reason resourceLimitReason(
            MpvResourcePressureController.Decision resource) {
        return resource.inputLevel() == MpvResourcePressurePolicy.Level.HARD
                ? Reason.RESOURCE_HARD_PRESSURE
                : Reason.RESOURCE_LIMIT;
    }

    private void observeDemand(
            MpvForwardCachePolicy.Assessment assessment,
            Trigger trigger,
            long nowElapsedMs) {
        if (trigger != Trigger.RUNTIME) return;
        if (!assessment.mediaReliable()) {
            demandCandidateBytes = -1;
            demandCandidateSinceElapsedMs = -1;
            demandSamples = 0;
            lastDemandSampleAtElapsedMs = nowElapsedMs;
            return;
        }
        long candidate = assessment.mediaTargetBytes();
        if (candidate != demandCandidateBytes) {
            demandCandidateBytes = candidate;
            demandCandidateSinceElapsedMs = nowElapsedMs;
            demandSamples = 1;
        } else if (nowElapsedMs > lastDemandSampleAtElapsedMs
                && demandSamples < Integer.MAX_VALUE) {
            demandSamples++;
        }
        lastDemandSampleAtElapsedMs = nowElapsedMs;
    }

    private void observeMemory(MpvForwardCachePolicy.Assessment assessment) {
        long sampledAt = assessment.memorySampleAtElapsedMs();
        if (sampledAt < 0 || sampledAt <= lastMemorySampleAtElapsedMs) {
            lastSafeTargetBytes = assessment.safeTargetBytes();
            return;
        }
        lastMemorySampleAtElapsedMs = sampledAt;
        PlaybackAutoContext.MemoryPressure pressure = assessment.pressureUsable()
                ? assessment.memoryPressure() : PlaybackAutoContext.MemoryPressure.UNKNOWN;
        lastObservedPressure = pressure;
        if (pressure == PlaybackAutoContext.MemoryPressure.NORMAL
                && assessment.snapshotUsable()
                && recoveryClass != RecoveryClass.NONE) {
            if (normalSinceElapsedMs < 0) normalSinceElapsedMs = sampledAt;
            if (normalSamples < Integer.MAX_VALUE) normalSamples++;
        } else if (pressure != PlaybackAutoContext.MemoryPressure.NORMAL) {
            normalSinceElapsedMs = -1;
            normalSamples = 0;
        }
        lastSafeTargetBytes = assessment.safeTargetBytes();
    }

    private void enterCapacityReduction(
            long previousSafeTargetBytes,
            long safeTargetBytes,
            long nowElapsedMs) {
        if (recoveryClass.severity < RecoveryClass.CAPACITY.severity) {
            recoveryClass = RecoveryClass.CAPACITY;
        }
        if (previousSafeTargetBytes < 0 || safeTargetBytes < previousSafeTargetBytes) {
            lastPressureAtElapsedMs = Math.max(lastPressureAtElapsedMs, nowElapsedMs);
        } else if (lastPressureAtElapsedMs < 0) {
            lastPressureAtElapsedMs = nowElapsedMs;
        }
        normalSinceElapsedMs = -1;
        normalSamples = 0;
    }

    private void enterPressure(
            RecoveryClass pressureClass,
            long sampledAtElapsedMs,
            long nowElapsedMs) {
        recoveryClass = RecoveryClass.max(recoveryClass, pressureClass);
        long pressureAt = sampledAtElapsedMs >= 0 ? sampledAtElapsedMs : nowElapsedMs;
        lastPressureAtElapsedMs = Math.max(lastPressureAtElapsedMs, pressureAt);
        normalSinceElapsedMs = -1;
        normalSamples = 0;
        if (pressureClass == RecoveryClass.CRITICAL) {
            moderateTargetBytes = MpvForwardCachePolicy.MIN_FORWARD_BYTES;
        }
    }

    private long cooldownRemainingMs(long nowElapsedMs) {
        if (recoveryClass == RecoveryClass.NONE || lastPressureAtElapsedMs < 0) return 0;
        long cooldown = recoveryClass == RecoveryClass.CRITICAL
                ? CRITICAL_COOLDOWN_MS : MODERATE_COOLDOWN_MS;
        return Math.max(0, cooldown - elapsed(nowElapsedMs, lastPressureAtElapsedMs));
    }

    private Decision remember(Decision decision) {
        lastDecision = decision;
        return decision;
    }

    private boolean isCurrent(PlaybackAutoContext.SessionToken token) {
        return token != null && token.active() && token.equals(session);
    }

    private void reset(PlaybackAutoContext.SessionToken token) {
        session = token == null ? PlaybackAutoContext.SessionToken.none() : token;
        state = State.IDLE;
        baselineInitialized = false;
        initialBaselineBytes = -1;
        controlledTargetBytes = -1;
        nativeTargetBytes = -1;
        evaluations = 0;
        applyAttempts = 0;
        lastApplySucceeded = false;
        clearHysteresis();
        lastDecision = Decision.hold(
                Trigger.BASELINE, Reason.WAITING_FOR_BASELINE,
                -1, -1, -1, 0, 0);
    }

    private void clearHysteresis() {
        lastSafeTargetBytes = -1;
        lastMemorySampleAtElapsedMs = -1;
        lastObservedPressure = PlaybackAutoContext.MemoryPressure.UNKNOWN;
        recoveryClass = RecoveryClass.NONE;
        moderateTargetBytes = -1;
        lastPressureAtElapsedMs = -1;
        normalSinceElapsedMs = -1;
        normalSamples = 0;
        demandCandidateBytes = -1;
        demandCandidateSinceElapsedMs = -1;
        demandSamples = 0;
        lastDemandSampleAtElapsedMs = -1;
        lastIncreaseAtElapsedMs = -1;
        lastDecreaseAtElapsedMs = -1;
        resourceRecoveryPending = false;
    }

    private static long elapsed(long nowElapsedMs, long thenElapsedMs) {
        if (nowElapsedMs < 0 || thenElapsedMs < 0) return 0;
        return Math.max(0, nowElapsedMs - thenElapsedMs);
    }

    public enum Trigger {
        BASELINE("baseline"),
        RUNTIME("runtime"),
        MEMORY("memory"),
        RESOURCE("resource"),
        REBUILD("rebuild");

        private final String label;

        Trigger(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Action {
        HOLD("hold"),
        APPLY("apply");

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
        WAITING_FOR_BASELINE("waiting-for-baseline"),
        APPLY_PENDING("apply-pending"),
        CRITICAL_PRESSURE("critical-pressure"),
        MODERATE_PRESSURE("moderate-pressure"),
        CAPACITY_REDUCTION("capacity-reduction"),
        RESOURCE_HARD_PRESSURE("resource-hard-pressure"),
        RESOURCE_LIMIT("resource-limit"),
        RESOURCE_EXPANSION_HOLD("resource-expansion-hold"),
        RESOURCE_RECOVERY_STEP("resource-recovery-step"),
        RESOURCE_CONTEXT_HOLD("resource-context-hold"),
        RECOVERY_WAIT("recovery-wait"),
        RECOVERY_STEP("recovery-step"),
        CONTEXT_RESTORE("context-restore"),
        UNKNOWN_MEDIA_HOLD("unknown-media-hold"),
        DEMAND_DECREASE_WAIT("demand-decrease-wait"),
        DEMAND_DECREASE_STEP("demand-decrease-step"),
        DEMAND_INCREASE_WAIT("demand-increase-wait"),
        DEMAND_INCREASE_STEP("demand-increase-step"),
        TARGET_STABLE("target-stable");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum State {
        IDLE("idle"),
        INACTIVE("inactive"),
        SUPPRESSED("suppressed"),
        WAITING_FOR_BASELINE("waiting-for-baseline"),
        ACTIVE("active"),
        DEMAND_WAIT("demand-wait"),
        RECOVERY_WAIT("recovery-wait"),
        CONTEXT_RESTORE_PENDING("context-restore-pending"),
        APPLY_READY("apply-ready"),
        APPLYING("applying"),
        STAGED("staged"),
        APPLIED("applied"),
        FAILED("failed");

        private final String label;

        State(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum RecoveryClass {
        NONE("none", 0),
        CAPACITY("capacity", 1),
        MODERATE("moderate", 2),
        CRITICAL("critical", 3);

        private final String label;
        private final int severity;

        RecoveryClass(String label, int severity) {
            this.label = label;
            this.severity = severity;
        }

        static RecoveryClass max(RecoveryClass first, RecoveryClass second) {
            RecoveryClass left = first == null ? NONE : first;
            RecoveryClass right = second == null ? NONE : second;
            return left.severity >= right.severity ? left : right;
        }

        public String label() {
            return label;
        }
    }

    public record Decision(
            Action action,
            Trigger trigger,
            Reason reason,
            long oldNativeTargetBytes,
            long oldControlledTargetBytes,
            long targetBytes,
            long safeTargetBytes,
            int normalSamples,
            long cooldownRemainingMs) {

        public Decision {
            action = action == null ? Action.HOLD : action;
            trigger = trigger == null ? Trigger.RUNTIME : trigger;
            reason = reason == null ? Reason.TARGET_STABLE : reason;
            targetBytes = targetBytes < 0 ? -1
                    : MpvForwardCachePolicy.normalizeTier(targetBytes);
            safeTargetBytes = safeTargetBytes < 0 ? -1
                    : MpvForwardCachePolicy.normalizeTier(safeTargetBytes);
            normalSamples = Math.max(0, normalSamples);
            cooldownRemainingMs = Math.max(0, cooldownRemainingMs);
        }

        static Decision hold(
                Trigger trigger,
                Reason reason,
                long oldNativeTargetBytes,
                long oldControlledTargetBytes,
                long safeTargetBytes,
                int normalSamples,
                long cooldownRemainingMs) {
            return new Decision(Action.HOLD, trigger, reason,
                    oldNativeTargetBytes, oldControlledTargetBytes,
                    oldControlledTargetBytes, safeTargetBytes,
                    normalSamples, cooldownRemainingMs);
        }

        public boolean requestsApply() {
            return action == Action.APPLY;
        }

        public String targetLabel() {
            return targetBytes < 0 ? "unchanged" : "forward-" + targetBytes;
        }
    }

    public record Snapshot(
            PlaybackAutoContext.SessionToken session,
            State state,
            boolean baselineInitialized,
            long initialBaselineBytes,
            long controlledTargetBytes,
            long nativeTargetBytes,
            long safeTargetBytes,
            RecoveryClass recoveryClass,
            PlaybackAutoContext.MemoryPressure observedPressure,
            long lastPressureAtElapsedMs,
            long normalSinceElapsedMs,
            int normalSamples,
            long demandCandidateBytes,
            long demandCandidateSinceElapsedMs,
            int demandSamples,
            long lastIncreaseAtElapsedMs,
            long lastDecreaseAtElapsedMs,
            boolean resourceRecoveryPending,
            int evaluations,
            int applyAttempts,
            boolean lastApplySucceeded,
            Decision lastDecision) {
    }
}
