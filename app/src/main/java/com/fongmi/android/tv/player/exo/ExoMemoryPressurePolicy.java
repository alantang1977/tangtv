package com.fongmi.android.tv.player.exo;

import com.fongmi.android.tv.player.PlaybackAutoContext;

/** Pure EXO policy for immediate memory degradation and delayed tier recovery. */
final class ExoMemoryPressurePolicy {

    static final long NORMAL_STABLE_WINDOW_MS = 30_000L;
    static final long MODERATE_COOLDOWN_MS = 30_000L;
    static final long CRITICAL_COOLDOWN_MS = 60_000L;
    static final long RECOVERY_STEP_INTERVAL_MS = 30_000L;

    private ExoMemoryPressurePolicy() {
    }

    static Decision resolve(
            Decision previous,
            int baselineTargetBytes,
            int safeTargetBytes,
            Observation observation) {
        int baseline = normalizeTarget(baselineTargetBytes);
        int safe = Math.min(
                baseline,
                safeTargetBytes <= 0 ? baseline : normalizeTarget(safeTargetBytes));
        Observation observed = observation == null ? Observation.unknown() : observation;
        int previousSafe = previous == null ? baseline : previous.safeTargetBytes();
        Decision current = previous == null
                ? Decision.normal(baseline, safe)
                : previous.rebase(baseline, safe);

        long sampledAt = observed.sampledAtElapsedMs();
        boolean freshSample = sampledAt >= 0
                && (current.lastObservationAtElapsedMs() < 0
                || sampledAt > current.lastObservationAtElapsedMs());
        PlaybackAutoContext.MemoryPressure pressure = observed.usablePressure();
        if (!freshSample) {
            long conservativeAt = Math.max(
                    sampledAt,
                    current.lastObservationAtElapsedMs());
            if (sampledAt >= 0
                    && pressure == PlaybackAutoContext.MemoryPressure.CRITICAL
                    && (current.mode() != Mode.CRITICAL
                    || current.effectiveTargetBytes()
                    > ExoTargetBufferPolicy.MIN_TARGET_BYTES)) {
                return current.degrade(
                        ExoTargetBufferPolicy.MIN_TARGET_BYTES,
                        Mode.CRITICAL,
                        RecoveryClass.CRITICAL,
                        Reason.CRITICAL_PRESSURE,
                        pressure,
                        conservativeAt);
            }
            int moderateTarget = Math.min(
                    safe,
                    ExoTargetBufferPolicy.previousTierBytes(baseline));
            if (sampledAt >= 0
                    && pressure == PlaybackAutoContext.MemoryPressure.MODERATE
                    && (current.effectiveTargetBytes() > moderateTarget
                    || current.recoveryClass() != RecoveryClass.CRITICAL
                    && current.mode() != Mode.MODERATE)) {
                return current.degrade(
                        moderateTarget,
                        Mode.MODERATE,
                        RecoveryClass.max(
                                current.recoveryClass(),
                                RecoveryClass.MODERATE),
                        Reason.MODERATE_PRESSURE,
                        pressure,
                        conservativeAt);
            }
            if (sampledAt >= 0 && observed.snapshotUsable()
                    && safe < current.effectiveTargetBytes()) {
                return current.degrade(
                        safe,
                        Mode.CAPACITY_LIMITED,
                        RecoveryClass.max(
                                current.recoveryClass(),
                                RecoveryClass.CAPACITY),
                        Reason.CAPACITY_REDUCTION,
                        pressure,
                        conservativeAt);
            }
            return current.withObservation(
                    pressure,
                    current.degraded() ? Reason.STALE_HOLD : Reason.BASELINE,
                    current.lastObservationAtElapsedMs());
        }

        if (pressure == PlaybackAutoContext.MemoryPressure.CRITICAL) {
            return current.degrade(
                    ExoTargetBufferPolicy.MIN_TARGET_BYTES,
                    Mode.CRITICAL,
                    RecoveryClass.CRITICAL,
                    Reason.CRITICAL_PRESSURE,
                    pressure,
                    sampledAt);
        }
        if (pressure == PlaybackAutoContext.MemoryPressure.MODERATE) {
            int moderateTarget = Math.min(
                    safe,
                    ExoTargetBufferPolicy.previousTierBytes(baseline));
            return current.degrade(
                    moderateTarget,
                    Mode.MODERATE,
                    RecoveryClass.max(current.recoveryClass(), RecoveryClass.MODERATE),
                    Reason.MODERATE_PRESSURE,
                    pressure,
                    sampledAt);
        }
        boolean capacityLimited = observed.snapshotUsable() && safe < baseline;
        boolean capacityTightened = capacityLimited
                && (previous == null
                || !previous.degraded()
                || safe < previousSafe
                || current.effectiveTargetBytes() > safe);
        if (capacityTightened) {
            return current.degrade(
                    safe,
                    Mode.CAPACITY_LIMITED,
                    RecoveryClass.max(current.recoveryClass(), RecoveryClass.CAPACITY),
                    Reason.CAPACITY_REDUCTION,
                    pressure,
                    sampledAt);
        }

        boolean normalEvidence = pressure == PlaybackAutoContext.MemoryPressure.NORMAL
                && observed.snapshotUsable();
        if (!normalEvidence) {
            if (!current.degraded()) {
                return Decision.normal(baseline, safe).withObservation(
                        pressure,
                        Reason.BASELINE,
                        sampledAt);
            }
            return current.breakNormalStreak(
                    Mode.RECOVERY_WAIT,
                    Reason.UNKNOWN_HOLD,
                    pressure,
                    sampledAt);
        }
        if (!current.degraded()) {
            return Decision.normal(baseline, safe).withObservation(
                    pressure,
                    Reason.BASELINE,
                    sampledAt);
        }

        Decision recovering = current.observeNormal(pressure, sampledAt);
        long cooldownMs = recovering.recoveryClass() == RecoveryClass.CRITICAL
                ? CRITICAL_COOLDOWN_MS : MODERATE_COOLDOWN_MS;
        boolean stable = recovering.normalSamples() >= 2
                && elapsed(sampledAt, recovering.normalSinceElapsedMs()) >= NORMAL_STABLE_WINDOW_MS;
        boolean cooledDown = recovering.lastPressureAtElapsedMs() < 0
                || elapsed(sampledAt, recovering.lastPressureAtElapsedMs()) >= cooldownMs;
        boolean stepReady = recovering.lastRecoveryStepAtElapsedMs() < 0
                || elapsed(sampledAt, recovering.lastRecoveryStepAtElapsedMs())
                >= RECOVERY_STEP_INTERVAL_MS;
        if (!stable || !cooledDown || !stepReady) {
            return recovering.withMode(Mode.RECOVERY_WAIT, Reason.RECOVERY_WAIT);
        }

        int ceiling = Math.min(baseline, safe);
        if (recovering.effectiveTargetBytes() < ceiling) {
            int next = ExoTargetBufferPolicy.nextTierBytes(
                    recovering.effectiveTargetBytes(), ceiling);
            Decision stepped = recovering.withRecoveryStep(next, sampledAt);
            if (next < baseline) return stepped.withMode(Mode.RECOVERING, Reason.RECOVERY_STEP);
            return stepped.recovered(pressure, sampledAt);
        }
        if (recovering.effectiveTargetBytes() >= baseline) {
            return recovering.recovered(pressure, sampledAt);
        }
        return recovering.withMode(Mode.RECOVERY_WAIT, Reason.RECOVERY_WAIT);
    }

    private static int normalizeTarget(int bytes) {
        return ExoTargetBufferPolicy.tierForCapacity(
                Math.max(ExoTargetBufferPolicy.MIN_TARGET_BYTES, bytes));
    }

    private static long elapsed(long now, long then) {
        if (now < 0 || then < 0) return 0;
        return Math.max(0, now - then);
    }

    record Observation(
            PlaybackAutoContext.MemoryPressure pressure,
            boolean pressureUsable,
            boolean snapshotUsable,
            long sampledAtElapsedMs) {

        Observation {
            pressure = pressure == null
                    ? PlaybackAutoContext.MemoryPressure.UNKNOWN : pressure;
            sampledAtElapsedMs = sampledAtElapsedMs < 0 ? -1 : sampledAtElapsedMs;
        }

        static Observation unknown() {
            return new Observation(
                    PlaybackAutoContext.MemoryPressure.UNKNOWN,
                    false,
                    false,
                    -1);
        }

        PlaybackAutoContext.MemoryPressure usablePressure() {
            return pressureUsable
                    ? pressure : PlaybackAutoContext.MemoryPressure.UNKNOWN;
        }
    }

    record Decision(
            int baselineTargetBytes,
            int safeTargetBytes,
            int effectiveTargetBytes,
            Mode mode,
            Reason reason,
            PlaybackAutoContext.MemoryPressure observedPressure,
            RecoveryClass recoveryClass,
            long lastPressureAtElapsedMs,
            long normalSinceElapsedMs,
            int normalSamples,
            long lastRecoveryStepAtElapsedMs,
            long lastObservationAtElapsedMs) {

        Decision {
            baselineTargetBytes = normalizeTarget(baselineTargetBytes);
            safeTargetBytes = Math.min(
                    baselineTargetBytes,
                    normalizeTarget(safeTargetBytes));
            effectiveTargetBytes = Math.min(
                    baselineTargetBytes,
                    normalizeTarget(effectiveTargetBytes));
            mode = mode == null ? Mode.NORMAL : mode;
            reason = reason == null ? Reason.BASELINE : reason;
            observedPressure = observedPressure == null
                    ? PlaybackAutoContext.MemoryPressure.UNKNOWN : observedPressure;
            recoveryClass = recoveryClass == null ? RecoveryClass.NONE : recoveryClass;
            normalSamples = Math.max(0, normalSamples);
        }

        static Decision normal(int baselineTargetBytes, int safeTargetBytes) {
            int baseline = normalizeTarget(baselineTargetBytes);
            int safe = Math.min(baseline, normalizeTarget(safeTargetBytes));
            return new Decision(
                    baseline,
                    safe,
                    safe,
                    Mode.NORMAL,
                    Reason.BASELINE,
                    PlaybackAutoContext.MemoryPressure.UNKNOWN,
                    RecoveryClass.NONE,
                    -1,
                    -1,
                    0,
                    -1,
                    -1);
        }

        boolean degraded() {
            return mode != Mode.NORMAL || effectiveTargetBytes < baselineTargetBytes;
        }

        boolean preloadPaused() {
            return degraded();
        }

        boolean backBufferSuppressed() {
            return degraded();
        }

        boolean expansionAllowed() {
            return !degraded();
        }

        long cooldownRemainingMs(long nowElapsedMs) {
            if (!degraded() || lastPressureAtElapsedMs < 0) return 0;
            long cooldown = recoveryClass == RecoveryClass.CRITICAL
                    ? CRITICAL_COOLDOWN_MS : MODERATE_COOLDOWN_MS;
            return Math.max(0, cooldown - elapsed(nowElapsedMs, lastPressureAtElapsedMs));
        }

        private Decision rebase(int baselineTargetBytes, int safeTargetBytes) {
            int baseline = normalizeTarget(baselineTargetBytes);
            int safe = Math.min(baseline, normalizeTarget(safeTargetBytes));
            int effective = degraded()
                    ? Math.min(effectiveTargetBytes, baseline) : baseline;
            return new Decision(
                    baseline,
                    safe,
                    effective,
                    mode,
                    reason,
                    observedPressure,
                    recoveryClass,
                    lastPressureAtElapsedMs,
                    normalSinceElapsedMs,
                    normalSamples,
                    lastRecoveryStepAtElapsedMs,
                    lastObservationAtElapsedMs);
        }

        private Decision degrade(
                int targetBytes,
                Mode degradedMode,
                RecoveryClass degradedClass,
                Reason degradedReason,
                PlaybackAutoContext.MemoryPressure pressure,
                long sampledAt) {
            return new Decision(
                    baselineTargetBytes,
                    safeTargetBytes,
                    Math.min(effectiveTargetBytes, normalizeTarget(targetBytes)),
                    degradedMode,
                    degradedReason,
                    pressure,
                    degradedClass,
                    sampledAt,
                    -1,
                    0,
                    -1,
                    sampledAt);
        }

        private Decision observeNormal(
                PlaybackAutoContext.MemoryPressure pressure,
                long sampledAt) {
            long normalSince = normalSinceElapsedMs < 0
                    ? sampledAt : normalSinceElapsedMs;
            int samples = normalSamples == Integer.MAX_VALUE
                    ? Integer.MAX_VALUE : normalSamples + 1;
            return new Decision(
                    baselineTargetBytes,
                    safeTargetBytes,
                    effectiveTargetBytes,
                    Mode.RECOVERY_WAIT,
                    Reason.RECOVERY_WAIT,
                    pressure,
                    recoveryClass,
                    lastPressureAtElapsedMs,
                    normalSince,
                    samples,
                    lastRecoveryStepAtElapsedMs,
                    sampledAt);
        }

        private Decision breakNormalStreak(
                Mode nextMode,
                Reason nextReason,
                PlaybackAutoContext.MemoryPressure pressure,
                long sampledAt) {
            return new Decision(
                    baselineTargetBytes,
                    safeTargetBytes,
                    effectiveTargetBytes,
                    nextMode,
                    nextReason,
                    pressure,
                    recoveryClass,
                    lastPressureAtElapsedMs,
                    -1,
                    0,
                    lastRecoveryStepAtElapsedMs,
                    sampledAt);
        }

        private Decision withRecoveryStep(int targetBytes, long sampledAt) {
            return new Decision(
                    baselineTargetBytes,
                    safeTargetBytes,
                    targetBytes,
                    Mode.RECOVERING,
                    Reason.RECOVERY_STEP,
                    PlaybackAutoContext.MemoryPressure.NORMAL,
                    recoveryClass,
                    lastPressureAtElapsedMs,
                    normalSinceElapsedMs,
                    normalSamples,
                    sampledAt,
                    sampledAt);
        }

        private Decision recovered(
                PlaybackAutoContext.MemoryPressure pressure,
                long sampledAt) {
            return new Decision(
                    baselineTargetBytes,
                    safeTargetBytes,
                    baselineTargetBytes,
                    Mode.NORMAL,
                    Reason.RECOVERED,
                    pressure,
                    RecoveryClass.NONE,
                    -1,
                    -1,
                    0,
                    sampledAt,
                    sampledAt);
        }

        private Decision withMode(Mode nextMode, Reason nextReason) {
            return new Decision(
                    baselineTargetBytes,
                    safeTargetBytes,
                    effectiveTargetBytes,
                    nextMode,
                    nextReason,
                    observedPressure,
                    recoveryClass,
                    lastPressureAtElapsedMs,
                    normalSinceElapsedMs,
                    normalSamples,
                    lastRecoveryStepAtElapsedMs,
                    lastObservationAtElapsedMs);
        }

        private Decision withObservation(
                PlaybackAutoContext.MemoryPressure pressure,
                Reason nextReason,
                long sampledAt) {
            return new Decision(
                    baselineTargetBytes,
                    safeTargetBytes,
                    effectiveTargetBytes,
                    mode,
                    nextReason,
                    pressure,
                    recoveryClass,
                    lastPressureAtElapsedMs,
                    normalSinceElapsedMs,
                    normalSamples,
                    lastRecoveryStepAtElapsedMs,
                    sampledAt);
        }
    }

    enum Mode {
        NORMAL("normal"),
        MODERATE("moderate"),
        CRITICAL("critical"),
        CAPACITY_LIMITED("capacity-limited"),
        RECOVERY_WAIT("recovery-wait"),
        RECOVERING("recovering");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    enum Reason {
        BASELINE("baseline"),
        MODERATE_PRESSURE("moderate-pressure"),
        CRITICAL_PRESSURE("critical-pressure"),
        CAPACITY_REDUCTION("capacity-reduction"),
        UNKNOWN_HOLD("unknown-hold"),
        STALE_HOLD("stale-hold"),
        RECOVERY_WAIT("recovery-wait"),
        RECOVERY_STEP("recovery-step"),
        RECOVERED("recovered");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    enum RecoveryClass {
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

        String label() {
            return label;
        }
    }
}
