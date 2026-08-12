package com.fongmi.android.tv.player.mpv;

import com.fongmi.android.tv.player.PlaybackAutoContext;

/** Session-isolated behavior controller for MPV automatic back-cache updates. */
public final class MpvBackCacheController {

    public static final long EXPANSION_STEP_INTERVAL_MS = 30_000L;
    public static final long BEHAVIOR_IDLE_RESET_MS = 10L * 60L * 1_000L;
    public static final long RECOVERY_STABLE_MS = 30_000L;
    public static final long CAPACITY_COOLDOWN_MS = 30_000L;
    public static final long MODERATE_COOLDOWN_MS = 30_000L;
    public static final long CRITICAL_COOLDOWN_MS = 60_000L;

    private PlaybackAutoContext.SessionToken session = PlaybackAutoContext.SessionToken.none();
    private State state = State.IDLE;
    private boolean baselineInitialized;
    private long controlledTargetBytes = -1;
    private long nativeTargetBytes = -1;
    private long learnedTargetBytes;
    private long lastSafeBackBytes = -1;
    private int backwardSeekEvidence;
    private long lastBackwardSeekDistanceMs;
    private long lastBackwardSeekAtElapsedMs = -1;
    private long lastMemorySampleAtElapsedMs = -1;
    private PlaybackAutoContext.MemoryPressure lastObservedPressure =
            PlaybackAutoContext.MemoryPressure.UNKNOWN;
    private RecoveryClass recoveryClass = RecoveryClass.NONE;
    private long lastPressureAtElapsedMs = -1;
    private long normalSinceElapsedMs = -1;
    private int normalSamples;
    private long lastIncreaseAtElapsedMs = -1;
    private long lastDecreaseAtElapsedMs = -1;
    private int evaluations;
    private int applyAttempts;
    private boolean lastApplySucceeded;
    private Decision lastDecision = Decision.hold(
            Trigger.BASELINE,
            Reason.WAITING_FOR_BASELINE,
            -1,
            -1,
            0,
            0,
            0,
            0,
            0,
            0);

    public synchronized void beginSession(PlaybackAutoContext.SessionToken token) {
        reset(token);
    }

    public synchronized void endSession(PlaybackAutoContext.SessionToken token) {
        if (!isCurrent(token)) return;
        reset(PlaybackAutoContext.SessionToken.none());
    }

    public synchronized boolean recordBaseline(
            PlaybackAutoContext.SessionToken token,
            long baselineBytes,
            boolean preserveControlledTarget) {
        if (!isCurrent(token)) return false;
        long baseline = MpvBackCachePolicy.normalizeTier(baselineBytes);
        if (!baselineInitialized || !preserveControlledTarget) {
            controlledTargetBytes = baseline;
            clearBehavior();
            clearRecovery();
        }
        baselineInitialized = true;
        nativeTargetBytes = baseline;
        if (controlledTargetBytes < 0) controlledTargetBytes = baseline;
        state = controlledTargetBytes == nativeTargetBytes
                ? State.ACTIVE : State.CONTEXT_RESTORE_PENDING;
        lastApplySucceeded = true;
        return true;
    }

    public synchronized boolean syncNativeTarget(
            PlaybackAutoContext.SessionToken token,
            long targetBytes) {
        if (!isCurrent(token) || !baselineInitialized) return false;
        nativeTargetBytes = MpvBackCachePolicy.normalizeTier(targetBytes);
        if (state == State.CONTEXT_RESTORE_PENDING
                && nativeTargetBytes == controlledTargetBytes) {
            state = State.ACTIVE;
        }
        return true;
    }

    public synchronized void suppress(PlaybackAutoContext.SessionToken token) {
        if (!isCurrent(token)) return;
        baselineInitialized = false;
        controlledTargetBytes = -1;
        nativeTargetBytes = -1;
        clearBehavior();
        clearRecovery();
        state = State.SUPPRESSED;
    }

    public synchronized Decision evaluate(
            PlaybackAutoContext.SessionToken token,
            PlaybackAutoContext.SessionToken factsSession,
            MpvBackCachePolicy.Assessment assessment,
            Trigger trigger,
            MpvBackCachePolicy.SeekObservation seekObservation,
            long nowElapsedMs) {
        return evaluate(token, factsSession, assessment,
                MpvResourcePressureController.Decision.unrestricted(),
                trigger, seekObservation, nowElapsedMs);
    }

    public synchronized Decision evaluate(
            PlaybackAutoContext.SessionToken token,
            PlaybackAutoContext.SessionToken factsSession,
            MpvBackCachePolicy.Assessment assessment,
            MpvResourcePressureController.Decision resourceDecision,
            Trigger trigger,
            MpvBackCachePolicy.SeekObservation seekObservation,
            long nowElapsedMs) {
        Trigger source = trigger == null ? Trigger.RUNTIME : trigger;
        long now = Math.max(0, nowElapsedMs);
        if (!isCurrent(token) || factsSession == null || !token.equals(factsSession)) {
            return remember(hold(source, Reason.STALE_SESSION, 0, now));
        }
        if (state == State.APPLYING) {
            return remember(hold(source, Reason.APPLY_PENDING, lastSafeBackBytes, now));
        }
        MpvBackCachePolicy.Assessment current = assessment == null
                ? new MpvBackCachePolicy.Assessment(
                false,
                false,
                MpvBackCachePolicy.Reason.NOT_AUTOMATIC_MPV,
                0,
                MpvForwardCachePolicy.MIN_FORWARD_BYTES,
                MpvForwardCachePolicy.MIN_FORWARD_BYTES,
                PlaybackAutoContext.MemoryPressure.UNKNOWN,
                false,
                false,
                -1,
                false,
                false)
                : assessment;
        MpvResourcePressureController.Decision resource = resourceDecision == null
                ? MpvResourcePressureController.Decision.unrestricted()
                : resourceDecision;
        if (!current.active()) {
            state = current.reason() == MpvBackCachePolicy.Reason.CONFIG_PRIORITY
                    ? State.SUPPRESSED : State.INACTIVE;
            return remember(hold(source,
                    current.reason() == MpvBackCachePolicy.Reason.CONFIG_PRIORITY
                            ? Reason.CONFIG_PRIORITY : Reason.NOT_AUTOMATIC_MPV,
                    current.safeBackBytes(),
                    now));
        }
        if (!baselineInitialized) {
            state = State.WAITING_FOR_BASELINE;
            return remember(hold(source, Reason.WAITING_FOR_BASELINE,
                    current.safeBackBytes(), now));
        }
        if (evaluations < Integer.MAX_VALUE) evaluations++;

        long previousSafeBack = lastSafeBackBytes;
        observeMemory(current);
        MpvBackCachePolicy.SeekObservation seek = seekObservation == null
                ? MpvBackCachePolicy.SeekObservation.none() : seekObservation;
        long resourceCeiling = resource.active()
                ? resource.backCeilingBytes()
                : MpvBackCachePolicy.MAX_BACK_BYTES;
        long effectiveSafeBack = Math.min(current.safeBackBytes(), resourceCeiling);
        boolean behaviorExpired = behaviorExpired(now);
        if (behaviorExpired) clearBehavior();
        if (source == Trigger.SEEK && current.eligible() && seek.qualifying()) {
            observeBackwardSeek(seek, now);
        }

        boolean canAdoptNative = source == Trigger.BASELINE || source == Trigger.REBUILD;
        if (behaviorExpired) {
            return targetOrAdopt(source, Reason.BEHAVIOR_EXPIRED, 0,
                    effectiveSafeBack, canAdoptNative, now);
        }
        if (!current.eligible()) {
            clearBehavior();
            clearRecovery();
            Reason reason = switch (current.reason()) {
                case NOT_SEEKABLE -> Reason.NOT_SEEKABLE;
                case LIVE_RESOURCE -> Reason.LIVE_RESOURCE;
                case OPAQUE_PATH -> Reason.OPAQUE_PATH;
                default -> Reason.INELIGIBLE;
            };
            return targetOrAdopt(source, reason, 0,
                    effectiveSafeBack, canAdoptNative, now);
        }

        if (current.forceZero()) {
            RecoveryClass pressureClass = switch (current.reason()) {
                case CRITICAL_PRESSURE -> RecoveryClass.CRITICAL;
                case MODERATE_PRESSURE -> RecoveryClass.MODERATE;
                default -> RecoveryClass.CAPACITY;
            };
            enterRecovery(pressureClass, previousSafeBack,
                    current.safeBackBytes(), current.memorySampleAtElapsedMs(), now);
            Reason reason = switch (current.reason()) {
                case CRITICAL_PRESSURE -> Reason.CRITICAL_PRESSURE;
                case MODERATE_PRESSURE -> Reason.MODERATE_PRESSURE;
                case MEMORY_UNKNOWN -> Reason.MEMORY_UNKNOWN;
                default -> Reason.NO_TOTAL_HEADROOM;
            };
            return targetOrAdopt(source, reason, 0,
                    effectiveSafeBack, canAdoptNative, now);
        }

        if (effectiveSafeBack < controlledTargetBytes) {
            boolean resourceLimited = resourceCeiling < current.safeBackBytes();
            if (!resourceLimited) {
                enterRecovery(RecoveryClass.CAPACITY, previousSafeBack,
                        current.safeBackBytes(), current.memorySampleAtElapsedMs(), now);
            }
            return targetOrAdopt(source,
                    resourceLimited ? resourceLimitReason(resource)
                            : Reason.CAPACITY_REDUCTION,
                    effectiveSafeBack, effectiveSafeBack,
                    canAdoptNative, now);
        }

        long unconstrainedDesired = Math.min(
                learnedTargetBytes, current.safeBackBytes());
        long desired = Math.min(unconstrainedDesired, resourceCeiling);
        if (unconstrainedDesired > controlledTargetBytes
                && desired <= controlledTargetBytes
                && resource.active()
                && !resource.expansionAllowed()) {
            state = State.BEHAVIOR_WAIT;
            return remember(hold(source, Reason.RESOURCE_EXPANSION_HOLD,
                    effectiveSafeBack, now));
        }
        if (recoveryClass != RecoveryClass.NONE) {
            if (controlledTargetBytes < desired
                    && resource.active()
                    && !resource.expansionAllowed()) {
                state = State.RECOVERY_WAIT;
                return remember(hold(source, Reason.RESOURCE_EXPANSION_HOLD,
                        effectiveSafeBack, now));
            }
            if (!current.normalMemoryEvidence()) {
                state = State.RECOVERY_WAIT;
                return remember(hold(source, Reason.RECOVERY_WAIT,
                        effectiveSafeBack, now));
            }
            boolean stable = normalSamples >= 2
                    && elapsed(now, normalSinceElapsedMs) >= RECOVERY_STABLE_MS;
            boolean cooledDown = cooldownRemainingMs(now) == 0;
            boolean stepReady = lastIncreaseAtElapsedMs < 0
                    || elapsed(now, lastIncreaseAtElapsedMs)
                    >= EXPANSION_STEP_INTERVAL_MS;
            if (controlledTargetBytes < desired) {
                if (!stable || !cooledDown || !stepReady) {
                    state = State.RECOVERY_WAIT;
                    return remember(hold(source, Reason.RECOVERY_WAIT,
                            effectiveSafeBack, now));
                }
                return targetOrAdopt(source, Reason.RECOVERY_STEP,
                        MpvBackCachePolicy.nextTier(controlledTargetBytes, desired),
                        effectiveSafeBack, false, now);
            }
            if (!stable || !cooledDown) {
                state = State.RECOVERY_WAIT;
                return remember(hold(source, Reason.RECOVERY_WAIT,
                        effectiveSafeBack, now));
            }
            clearRecovery();
        }

        if (nativeTargetBytes != controlledTargetBytes) {
            if (resource.active()
                    && !resource.expansionAllowed()
                    && nativeTargetBytes >= 0
                    && nativeTargetBytes < controlledTargetBytes) {
                return targetOrAdopt(source, Reason.RESOURCE_CONTEXT_HOLD,
                        nativeTargetBytes, effectiveSafeBack, true, now);
            }
            return targetOrAdopt(source, Reason.CONTEXT_RESTORE,
                    controlledTargetBytes, effectiveSafeBack, false, now);
        }

        if (desired > controlledTargetBytes) {
            if (resource.active() && !resource.expansionAllowed()) {
                state = State.BEHAVIOR_WAIT;
                return remember(hold(source, Reason.RESOURCE_EXPANSION_HOLD,
                        effectiveSafeBack, now));
            }
            boolean stepReady = lastIncreaseAtElapsedMs < 0
                    || elapsed(now, lastIncreaseAtElapsedMs)
                    >= EXPANSION_STEP_INTERVAL_MS;
            if (!stepReady) {
                state = State.BEHAVIOR_WAIT;
                return remember(hold(source, Reason.EXPANSION_COOLDOWN,
                        effectiveSafeBack, now));
            }
            return targetOrAdopt(source, Reason.BEHAVIOR_EXPANSION,
                    MpvBackCachePolicy.nextTier(controlledTargetBytes, desired),
                    effectiveSafeBack, false, now);
        }

        state = State.ACTIVE;
        Reason stableReason = source == Trigger.SEEK
                ? reasonForSeek(seek) : backwardSeekEvidence == 0
                ? Reason.WAITING_FOR_BACKWARD_SEEK : Reason.TARGET_STABLE;
        return remember(hold(source, stableReason, effectiveSafeBack, now));
    }

    /** Commits a controller action before the combined native update. */
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
        long previous = controlledTargetBytes;
        controlledTargetBytes = decision.targetBytes();
        nativeTargetBytes = decision.targetBytes();
        if (controlledTargetBytes > previous) {
            lastIncreaseAtElapsedMs = completedAt;
        } else if (controlledTargetBytes < previous) {
            lastDecreaseAtElapsedMs = completedAt;
        }
        state = staged ? State.STAGED : State.APPLIED;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                session,
                state,
                baselineInitialized,
                controlledTargetBytes,
                nativeTargetBytes,
                learnedTargetBytes,
                lastSafeBackBytes,
                backwardSeekEvidence,
                lastBackwardSeekDistanceMs,
                lastBackwardSeekAtElapsedMs,
                lastObservedPressure,
                recoveryClass,
                lastPressureAtElapsedMs,
                normalSinceElapsedMs,
                normalSamples,
                lastIncreaseAtElapsedMs,
                lastDecreaseAtElapsedMs,
                evaluations,
                applyAttempts,
                lastApplySucceeded,
                lastDecision);
    }

    private Decision targetOrAdopt(
            Trigger trigger,
            Reason reason,
            long requestedTargetBytes,
            long safeBackBytes,
            boolean canAdoptNative,
            long nowElapsedMs) {
        long target = Math.min(
                MpvBackCachePolicy.normalizeTier(requestedTargetBytes),
                MpvBackCachePolicy.normalizeTier(safeBackBytes));
        if (target == controlledTargetBytes && target == nativeTargetBytes) {
            state = holdState(reason);
            return remember(hold(trigger, reason, safeBackBytes, nowElapsedMs));
        }
        if (canAdoptNative && target == nativeTargetBytes) {
            controlledTargetBytes = target;
            state = holdState(reason);
            return remember(hold(trigger, reason, safeBackBytes, nowElapsedMs));
        }
        state = State.APPLY_READY;
        return remember(new Decision(
                Action.APPLY,
                trigger,
                reason,
                nativeTargetBytes,
                controlledTargetBytes,
                target,
                MpvBackCachePolicy.normalizeTier(safeBackBytes),
                learnedTargetBytes,
                backwardSeekEvidence,
                lastBackwardSeekDistanceMs,
                normalSamples,
                cooldownRemainingMs(nowElapsedMs)));
    }

    private Decision hold(
            Trigger trigger,
            Reason reason,
            long safeBackBytes,
            long nowElapsedMs) {
        return Decision.hold(
                trigger,
                reason,
                nativeTargetBytes,
                controlledTargetBytes,
                safeBackBytes,
                learnedTargetBytes,
                backwardSeekEvidence,
                lastBackwardSeekDistanceMs,
                normalSamples,
                cooldownRemainingMs(nowElapsedMs));
    }

    private void observeBackwardSeek(
            MpvBackCachePolicy.SeekObservation observation,
            long nowElapsedMs) {
        int increment = observation.large() && backwardSeekEvidence > 0 ? 2 : 1;
        backwardSeekEvidence = Math.min(4, backwardSeekEvidence + increment);
        learnedTargetBytes = MpvBackCachePolicy.learnedTier(backwardSeekEvidence);
        lastBackwardSeekDistanceMs = observation.distanceMs();
        lastBackwardSeekAtElapsedMs = nowElapsedMs;
    }

    private void observeMemory(MpvBackCachePolicy.Assessment assessment) {
        long sampledAt = assessment.memorySampleAtElapsedMs();
        if (sampledAt >= 0 && sampledAt > lastMemorySampleAtElapsedMs) {
            lastMemorySampleAtElapsedMs = sampledAt;
            lastObservedPressure = assessment.pressureUsable()
                    ? assessment.memoryPressure() : PlaybackAutoContext.MemoryPressure.UNKNOWN;
            if (assessment.normalMemoryEvidence() && recoveryClass != RecoveryClass.NONE) {
                if (normalSinceElapsedMs < 0) normalSinceElapsedMs = sampledAt;
                if (normalSamples < Integer.MAX_VALUE) normalSamples++;
            } else if (!assessment.normalMemoryEvidence()) {
                normalSinceElapsedMs = -1;
                normalSamples = 0;
            }
        }
        lastSafeBackBytes = assessment.safeBackBytes();
    }

    private void enterRecovery(
            RecoveryClass requestedClass,
            long previousSafeBackBytes,
            long safeBackBytes,
            long sampledAtElapsedMs,
            long nowElapsedMs) {
        RecoveryClass next = RecoveryClass.max(recoveryClass, requestedClass);
        boolean escalated = next.severity > recoveryClass.severity;
        boolean capacityDropped = previousSafeBackBytes < 0
                || safeBackBytes < previousSafeBackBytes;
        recoveryClass = next;
        if (escalated || capacityDropped || lastPressureAtElapsedMs < 0) {
            long pressureAt = sampledAtElapsedMs >= 0
                    ? sampledAtElapsedMs : nowElapsedMs;
            lastPressureAtElapsedMs = Math.max(lastPressureAtElapsedMs, pressureAt);
            normalSinceElapsedMs = -1;
            normalSamples = 0;
        }
    }

    private long cooldownRemainingMs(long nowElapsedMs) {
        if (recoveryClass == RecoveryClass.NONE || lastPressureAtElapsedMs < 0) return 0;
        long cooldown = switch (recoveryClass) {
            case CRITICAL -> CRITICAL_COOLDOWN_MS;
            case MODERATE -> MODERATE_COOLDOWN_MS;
            case CAPACITY -> CAPACITY_COOLDOWN_MS;
            case NONE -> 0;
        };
        return Math.max(0, cooldown - elapsed(nowElapsedMs, lastPressureAtElapsedMs));
    }

    private static State holdState(Reason reason) {
        if (reason == Reason.CRITICAL_PRESSURE
                || reason == Reason.MODERATE_PRESSURE
                || reason == Reason.MEMORY_UNKNOWN
                || reason == Reason.NO_TOTAL_HEADROOM
                || reason == Reason.CAPACITY_REDUCTION
                || reason == Reason.RESOURCE_HARD_PRESSURE
                || reason == Reason.RESOURCE_LIMIT
                || reason == Reason.RESOURCE_EXPANSION_HOLD
                || reason == Reason.RESOURCE_CONTEXT_HOLD
                || reason == Reason.RECOVERY_WAIT
                || reason == Reason.RECOVERY_STEP) {
            return State.RECOVERY_WAIT;
        }
        if (reason == Reason.EXPANSION_COOLDOWN
                || reason == Reason.FIRST_BACKWARD_SEEK
                || reason == Reason.WAITING_FOR_BACKWARD_SEEK) {
            return State.BEHAVIOR_WAIT;
        }
        return State.ACTIVE;
    }

    private static Reason resourceLimitReason(
            MpvResourcePressureController.Decision resource) {
        return resource.inputLevel() == MpvResourcePressurePolicy.Level.HARD
                ? Reason.RESOURCE_HARD_PRESSURE
                : Reason.RESOURCE_LIMIT;
    }

    private static Reason reasonForSeek(MpvBackCachePolicy.SeekObservation seek) {
        if (seek.qualifying()) {
            return Reason.FIRST_BACKWARD_SEEK;
        }
        return switch (seek.kind()) {
            case NOT_EXPLICIT_SEEK -> Reason.NOT_EXPLICIT_SEEK;
            case MEDIA_ITEM_CHANGE -> Reason.MEDIA_ITEM_CHANGE;
            case UNKNOWN_POSITION -> Reason.UNKNOWN_POSITION;
            case FORWARD_OR_EQUAL -> Reason.FORWARD_OR_EQUAL_SEEK;
            case TOO_SMALL -> Reason.SMALL_BACKWARD_SEEK;
            case BACKWARD -> Reason.FIRST_BACKWARD_SEEK;
        };
    }

    private void clearBehavior() {
        learnedTargetBytes = 0;
        backwardSeekEvidence = 0;
        lastBackwardSeekDistanceMs = 0;
        lastBackwardSeekAtElapsedMs = -1;
        lastIncreaseAtElapsedMs = -1;
        lastDecreaseAtElapsedMs = -1;
    }

    private boolean behaviorExpired(long nowElapsedMs) {
        return backwardSeekEvidence > 0
                && lastBackwardSeekAtElapsedMs >= 0
                && elapsed(nowElapsedMs, lastBackwardSeekAtElapsedMs)
                >= BEHAVIOR_IDLE_RESET_MS;
    }

    private void clearRecovery() {
        recoveryClass = RecoveryClass.NONE;
        lastPressureAtElapsedMs = -1;
        normalSinceElapsedMs = -1;
        normalSamples = 0;
    }

    private void reset(PlaybackAutoContext.SessionToken token) {
        session = token == null ? PlaybackAutoContext.SessionToken.none() : token;
        state = State.IDLE;
        baselineInitialized = false;
        controlledTargetBytes = -1;
        nativeTargetBytes = -1;
        lastSafeBackBytes = -1;
        lastMemorySampleAtElapsedMs = -1;
        lastObservedPressure = PlaybackAutoContext.MemoryPressure.UNKNOWN;
        evaluations = 0;
        applyAttempts = 0;
        lastApplySucceeded = false;
        clearBehavior();
        clearRecovery();
        lastDecision = Decision.hold(
                Trigger.BASELINE,
                Reason.WAITING_FOR_BASELINE,
                -1,
                -1,
                0,
                0,
                0,
                0,
                0,
                0);
    }

    private Decision remember(Decision decision) {
        lastDecision = decision;
        return decision;
    }

    private boolean isCurrent(PlaybackAutoContext.SessionToken token) {
        return token != null && token.active() && token.equals(session);
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
        SEEK("seek"),
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
        NOT_SEEKABLE("not-seekable"),
        LIVE_RESOURCE("live-resource"),
        OPAQUE_PATH("opaque-path"),
        INELIGIBLE("ineligible"),
        CRITICAL_PRESSURE("critical-pressure"),
        MODERATE_PRESSURE("moderate-pressure"),
        MEMORY_UNKNOWN("memory-unknown"),
        NO_TOTAL_HEADROOM("no-total-headroom"),
        CAPACITY_REDUCTION("capacity-reduction"),
        RESOURCE_HARD_PRESSURE("resource-hard-pressure"),
        RESOURCE_LIMIT("resource-limit"),
        RESOURCE_EXPANSION_HOLD("resource-expansion-hold"),
        RESOURCE_CONTEXT_HOLD("resource-context-hold"),
        RECOVERY_WAIT("recovery-wait"),
        RECOVERY_STEP("recovery-step"),
        CONTEXT_RESTORE("context-restore"),
        NOT_EXPLICIT_SEEK("not-explicit-seek"),
        MEDIA_ITEM_CHANGE("media-item-change"),
        UNKNOWN_POSITION("unknown-position"),
        FORWARD_OR_EQUAL_SEEK("forward-or-equal-seek"),
        SMALL_BACKWARD_SEEK("small-backward-seek"),
        FIRST_BACKWARD_SEEK("first-backward-seek"),
        BEHAVIOR_EXPIRED("behavior-expired"),
        WAITING_FOR_BACKWARD_SEEK("waiting-for-backward-seek"),
        EXPANSION_COOLDOWN("expansion-cooldown"),
        BEHAVIOR_EXPANSION("behavior-expansion"),
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
        BEHAVIOR_WAIT("behavior-wait"),
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
            long safeBackBytes,
            long learnedTargetBytes,
            int backwardSeekEvidence,
            long lastBackwardSeekDistanceMs,
            int normalSamples,
            long cooldownRemainingMs) {

        public Decision {
            action = action == null ? Action.HOLD : action;
            trigger = trigger == null ? Trigger.RUNTIME : trigger;
            reason = reason == null ? Reason.TARGET_STABLE : reason;
            targetBytes = targetBytes < 0 ? -1
                    : MpvBackCachePolicy.normalizeTier(targetBytes);
            safeBackBytes = MpvBackCachePolicy.normalizeTier(safeBackBytes);
            learnedTargetBytes = MpvBackCachePolicy.normalizeTier(learnedTargetBytes);
            backwardSeekEvidence = Math.max(0, backwardSeekEvidence);
            lastBackwardSeekDistanceMs = Math.max(0, lastBackwardSeekDistanceMs);
            normalSamples = Math.max(0, normalSamples);
            cooldownRemainingMs = Math.max(0, cooldownRemainingMs);
        }

        static Decision hold(
                Trigger trigger,
                Reason reason,
                long oldNativeTargetBytes,
                long oldControlledTargetBytes,
                long safeBackBytes,
                long learnedTargetBytes,
                int backwardSeekEvidence,
                long lastBackwardSeekDistanceMs,
                int normalSamples,
                long cooldownRemainingMs) {
            return new Decision(
                    Action.HOLD,
                    trigger,
                    reason,
                    oldNativeTargetBytes,
                    oldControlledTargetBytes,
                    oldControlledTargetBytes,
                    safeBackBytes,
                    learnedTargetBytes,
                    backwardSeekEvidence,
                    lastBackwardSeekDistanceMs,
                    normalSamples,
                    cooldownRemainingMs);
        }

        public boolean requestsApply() {
            return action == Action.APPLY;
        }

        public String targetLabel() {
            return targetBytes < 0 ? "unchanged" : "back-" + targetBytes;
        }
    }

    public record Snapshot(
            PlaybackAutoContext.SessionToken session,
            State state,
            boolean baselineInitialized,
            long controlledTargetBytes,
            long nativeTargetBytes,
            long learnedTargetBytes,
            long safeBackBytes,
            int backwardSeekEvidence,
            long lastBackwardSeekDistanceMs,
            long lastBackwardSeekAtElapsedMs,
            PlaybackAutoContext.MemoryPressure observedPressure,
            RecoveryClass recoveryClass,
            long lastPressureAtElapsedMs,
            long normalSinceElapsedMs,
            int normalSamples,
            long lastIncreaseAtElapsedMs,
            long lastDecreaseAtElapsedMs,
            int evaluations,
            int applyAttempts,
            boolean lastApplySucceeded,
            Decision lastDecision) {
    }
}
