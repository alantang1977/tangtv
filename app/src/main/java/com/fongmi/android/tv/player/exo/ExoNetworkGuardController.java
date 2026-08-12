package com.fongmi.android.tv.player.exo;

/** Deadline-aware, hysteretic controller for adaptive EXO network protection. */
public final class ExoNetworkGuardController {

    public static final long OBSERVE_INTERVAL_MS = 5_000;
    public static final long CONTROL_INTERVAL_MS = 1_000;
    static final long MIN_TREND_WINDOW_MS = 10_000;
    static final long REBUFFER_MIN_TREND_WINDOW_MS = 5_000;
    static final long ENTRY_CONFIRM_MS = 10_000;
    static final long URGENT_ENTRY_CONFIRM_MS = 3_000;
    static final long RECOVERY_CONFIRM_MS = 8_000;
    static final long FULL_BUFFER_RECOVERY_CONFIRM_MS = 12_000;
    static final long ADJUSTMENT_COOLDOWN_MS = 1_000;
    static final long RISK_BUFFER_HEADROOM_MS = 20_000;
    static final long RECOVERY_BUFFER_HEADROOM_MS = 5_000;
    static final long RISK_TIME_TO_RESERVE_MS = 240_000;
    static final long DEEP_PROTECTION_TIME_TO_RESERVE_MS = 120_000;
    static final long URGENT_TIME_TO_RESERVE_MS = 30_000;
    static final long RAMP_RESERVE_MARGIN_MS = 5_000;
    static final long MAX_RAMP_ELAPSED_MS = 2_000;
    static final long DECLINE_DEADBAND_MS_PER_SECOND = -15;
    static final long PROTECTED_DECLINE_DEADBAND_MS_PER_SECOND = -2;
    static final float SUPPORTED_RECOVERY_SPEED = 0.998f;
    static final float RECOVERY_SPEED_HEADROOM = 0.03f;
    static final float SAFETY_MARGIN = 0.003f;
    static final float PROPORTIONAL_GAIN = 0.20f;
    static final float LIGHT_MAX_SLEW_PER_SECOND = 0.003f;
    static final float DEEP_MAX_SLEW_PER_SECOND = 0.006f;
    static final float URGENT_MAX_SLEW_PER_SECOND = 0.010f;
    static final float RECOVERY_SLEW_PER_SECOND = 0.002f;
    static final float MIN_CHANGE = 0.001f;

    private static final long UNSET = Long.MIN_VALUE;
    private static final float EPSILON = 0.0005f;

    private State state = State.NORMAL;
    private ProtectionTier tier = ProtectionTier.NONE;
    private long warningSinceMs = UNSET;
    private long recoverySinceMs = UNSET;
    private long lastAdjustmentMs = UNSET;
    private int lastRebufferCount;

    public void reset() {
        state = State.NORMAL;
        tier = ProtectionTier.NONE;
        warningSinceMs = UNSET;
        recoverySinceMs = UNSET;
        lastAdjustmentMs = UNSET;
        lastRebufferCount = 0;
    }

    public void disrupt(float currentSpeed) {
        warningSinceMs = UNSET;
        recoverySinceMs = UNSET;
        state = currentSpeed < 1f - EPSILON ? State.PROTECT : State.NORMAL;
        tier = tierForSpeed(currentSpeed);
    }

    public State getState() {
        return state;
    }

    public ProtectionTier getTier() {
        return tier;
    }

    public Decision evaluate(Input input) {
        float currentSpeed = clamp(input.currentSpeed(), 0.25f, 1f);
        float minimumSpeed = clamp(input.minimumSpeed(), ExoNetworkProtectionPolicy.AUTO_MIN_SPEED, 1f);
        Metrics metrics = metrics(input, currentSpeed, minimumSpeed);
        if (!input.eligible()) {
            boolean changed = currentSpeed < 1f - EPSILON;
            reset();
            return decision(state, tier, 1f, changed, "ineligible", metrics, 0f, 0f, true);
        }

        boolean rebuffered = input.rebufferCount() > lastRebufferCount;
        lastRebufferCount = Math.max(lastRebufferCount, input.rebufferCount());
        if (!input.ready() || !input.playing()) return hold(currentSpeed, input, metrics, "inactive", true);
        if (!input.loading()) return evaluateFullBufferRecovery(input, currentSpeed, metrics);
        boolean rebufferTrendReady = rebuffered
                && input.trendKnown()
                && input.trendWindowMs() >= REBUFFER_MIN_TREND_WINDOW_MS
                && input.bufferedMs() <= metrics.safeBufferMs() + RECOVERY_BUFFER_HEADROOM_MS;
        if (!input.trendKnown()
                || (input.trendWindowMs() < MIN_TREND_WINDOW_MS
                && !rebufferTrendReady)) {
            if (rebuffered) state = State.WARNING;
            else state = currentSpeed < 1f - EPSILON ? State.PROTECT : State.NORMAL;
            tier = tierForSpeed(currentSpeed);
            return hold(currentSpeed, input, metrics, "trend-warmup", true);
        }

        long declineThreshold = currentSpeed < 1f - EPSILON ? PROTECTED_DECLINE_DEADBAND_MS_PER_SECOND : DECLINE_DEADBAND_MS_PER_SECOND;
        boolean decliningRisk = input.slopeMsPerSecond() <= declineThreshold
                && (input.bufferedMs() <= metrics.safeBufferMs() + RISK_BUFFER_HEADROOM_MS || metrics.timeToReserveMs() <= RISK_TIME_TO_RESERVE_MS);
        if (decliningRisk) return evaluateDecliningRisk(input, currentSpeed, minimumSpeed, metrics, rebuffered);

        warningSinceMs = UNSET;
        if (currentSpeed < 1f - EPSILON) return evaluateRecovery(input, currentSpeed, metrics);

        recoverySinceMs = UNSET;
        state = State.NORMAL;
        tier = ProtectionTier.NONE;
        return hold(currentSpeed, input, metrics, "healthy", true);
    }

    private Decision evaluateDecliningRisk(Input input, float currentSpeed, float minimumSpeed, Metrics metrics, boolean rebuffered) {
        recoverySinceMs = UNSET;
        float calculatedTarget = metrics.calculatedTargetSpeed();
        ProtectionTier requestedTier = calculatedTarget < ExoNetworkProtectionPolicy.PREFERRED_MIN_SPEED - EPSILON ? ProtectionTier.DEEP : ProtectionTier.LIGHT;
        boolean dualTrendEvidence = input.fastSlopeMsPerSecond() <= DECLINE_DEADBAND_MS_PER_SECOND
                && input.slowSlopeMsPerSecond() <= DECLINE_DEADBAND_MS_PER_SECOND;
        boolean deepNeeded = metrics.timeToReserveMs() <= DEEP_PROTECTION_TIME_TO_RESERVE_MS
                || input.bufferedMs() <= metrics.safeBufferMs() + RECOVERY_BUFFER_HEADROOM_MS
                || rebuffered;
        boolean deepAlreadyActive = tier == ProtectionTier.DEEP || currentSpeed < ExoNetworkProtectionPolicy.PREFERRED_MIN_SPEED - EPSILON;
        String reason = "sustained-risk";
        if (requestedTier == ProtectionTier.DEEP && !deepAlreadyActive && (!dualTrendEvidence || !deepNeeded)) {
            calculatedTarget = Math.max(calculatedTarget, ExoNetworkProtectionPolicy.PREFERRED_MIN_SPEED);
            requestedTier = ProtectionTier.LIGHT;
            reason = !dualTrendEvidence ? "deep-confidence-building" : "light-protection-first";
        }

        state = State.WARNING;
        tier = requestedTier;
        boolean urgent = metrics.timeToReserveMs() <= URGENT_TIME_TO_RESERVE_MS || input.bufferedMs() <= metrics.safeBufferMs();
        long confirmationMs = urgent ? URGENT_ENTRY_CONFIRM_MS : ENTRY_CONFIRM_MS;
        if (warningSinceMs == UNSET) warningSinceMs = rebuffered ? input.nowMs() - confirmationMs : input.nowMs();
        if (input.nowMs() - warningSinceMs < confirmationMs) {
            return decision(state, tier, currentSpeed, false, "risk-confirming", metrics.withCalculatedTarget(calculatedTarget), 0f, 0f, true);
        }

        if (calculatedTarget >= currentSpeed - MIN_CHANGE) {
            state = currentSpeed < 1f - EPSILON ? State.PROTECT : State.WARNING;
            tier = tierForSpeed(currentSpeed);
            return decision(state, tier, currentSpeed, false, "within-deadband", metrics.withCalculatedTarget(calculatedTarget), 0f, 0f, true);
        }

        Ramp ramp = calculateRamp(input.nowMs(), currentSpeed, calculatedTarget, metrics.timeToReserveMs(), requestedTier);
        if (!ramp.feasible()) reason = "deadline-emergency-rescue";
        if (!canAdjust(input.nowMs())) {
            return decision(state, tier, currentSpeed, false, reason + "-cooldown", metrics.withCalculatedTarget(calculatedTarget), ramp.requiredSlewPerSecond(), 0f, true);
        }

        float target = roundThousandth(Math.max(calculatedTarget, currentSpeed - ramp.step()));
        if (currentSpeed - target < MIN_CHANGE) {
            return decision(state, tier, currentSpeed, false, reason + "-deadband", metrics.withCalculatedTarget(calculatedTarget), ramp.requiredSlewPerSecond(), 0f, true);
        }
        lastAdjustmentMs = input.nowMs();
        state = State.PROTECT;
        tier = requestedTier;
        return decision(state, tier, target, true, reason, metrics.withCalculatedTarget(calculatedTarget), ramp.requiredSlewPerSecond(), ramp.appliedSlewPerSecond(), true);
    }

    private Decision evaluateRecovery(Input input, float currentSpeed, Metrics metrics) {
        state = State.PROTECT;
        tier = tierForSpeed(currentSpeed);
        long recoveryBuffer = Math.max(15_000, Math.round(metrics.safeBufferMs() * 0.65f));
        float requiredRecoverySpeed = Math.max(SUPPORTED_RECOVERY_SPEED, currentSpeed + RECOVERY_SPEED_HEADROOM);
        boolean recoveryReady = metrics.supportedSpeed() >= requiredRecoverySpeed
                && input.slopeMsPerSecond() >= 0
                && input.bufferedMs() >= recoveryBuffer;
        if (!recoveryReady) {
            recoverySinceMs = UNSET;
            return hold(currentSpeed, input, metrics, "protected-stable", true);
        }
        state = State.RECOVERY;
        if (recoverySinceMs == UNSET) recoverySinceMs = input.nowMs();
        if (input.nowMs() - recoverySinceMs < RECOVERY_CONFIRM_MS || !canAdjust(input.nowMs())) {
            return hold(currentSpeed, input, metrics, "recovery-confirming", true);
        }
        return adjustUp(input, currentSpeed, metrics, "capacity-recovered");
    }

    private Decision evaluateFullBufferRecovery(Input input, float currentSpeed, Metrics metrics) {
        warningSinceMs = UNSET;
        long fullBuffer = Math.max(20_000, Math.round(metrics.safeBufferMs() * 0.85f));
        if (currentSpeed >= 1f - EPSILON || input.bufferedMs() < fullBuffer) {
            recoverySinceMs = UNSET;
            state = currentSpeed < 1f - EPSILON ? State.PROTECT : State.NORMAL;
            tier = tierForSpeed(currentSpeed);
            return hold(currentSpeed, input, metrics, "loader-idle", true);
        }
        state = State.RECOVERY;
        tier = tierForSpeed(currentSpeed);
        if (recoverySinceMs == UNSET) recoverySinceMs = input.nowMs();
        if (input.nowMs() - recoverySinceMs < FULL_BUFFER_RECOVERY_CONFIRM_MS || !canAdjust(input.nowMs())) {
            return hold(currentSpeed, input, metrics, "full-buffer-confirming", true);
        }
        return adjustUp(input, currentSpeed, metrics, "full-buffer");
    }

    private Decision adjustUp(Input input, float currentSpeed, Metrics metrics, String reason) {
        long elapsedMs = adjustmentElapsedMs(input.nowMs());
        float step = Math.max(MIN_CHANGE, RECOVERY_SLEW_PER_SECOND * elapsedMs / 1_000f);
        float target = roundThousandth(Math.min(1f, currentSpeed + step));
        if (target - currentSpeed < MIN_CHANGE) return hold(currentSpeed, input, metrics, reason + "-deadband", true);
        lastAdjustmentMs = input.nowMs();
        if (target >= 1f - EPSILON) {
            state = State.NORMAL;
            tier = ProtectionTier.NONE;
        } else {
            state = State.RECOVERY;
            tier = tierForSpeed(target);
        }
        float appliedSlew = (target - currentSpeed) * 1_000f / elapsedMs;
        return decision(state, tier, target, true, reason, metrics, 0f, appliedSlew, true);
    }

    private Ramp calculateRamp(long nowMs, float currentSpeed, float targetSpeed, long timeToReserveMs, ProtectionTier requestedTier) {
        float error = Math.max(0f, currentSpeed - targetSpeed);
        long availableRampTimeMs = timeToReserveMs == Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(0, timeToReserveMs - RAMP_RESERVE_MARGIN_MS);
        float requiredSlew = availableRampTimeMs == Long.MAX_VALUE ? 0f
                : availableRampTimeMs <= 0 ? Float.POSITIVE_INFINITY
                : error * 1_000f / availableRampTimeMs;
        float maximumSlew = timeToReserveMs <= URGENT_TIME_TO_RESERVE_MS
                ? URGENT_MAX_SLEW_PER_SECOND
                : requestedTier == ProtectionTier.DEEP ? DEEP_MAX_SLEW_PER_SECOND : LIGHT_MAX_SLEW_PER_SECOND;
        boolean feasible = requiredSlew <= maximumSlew + EPSILON;
        long elapsedMs = adjustmentElapsedMs(nowMs);
        float proportionalSlew = PROPORTIONAL_GAIN * error;
        float appliedSlew = Math.min(maximumSlew, Math.max(requiredSlew, proportionalSlew));
        float step = Math.min(error, Math.max(MIN_CHANGE, appliedSlew * elapsedMs / 1_000f));
        return new Ramp(step, requiredSlew, step * 1_000f / elapsedMs, feasible);
    }

    private long adjustmentElapsedMs(long nowMs) {
        if (lastAdjustmentMs == UNSET) return CONTROL_INTERVAL_MS;
        return Math.max(1, Math.min(MAX_RAMP_ELAPSED_MS, nowMs - lastAdjustmentMs));
    }

    private boolean canAdjust(long nowMs) {
        return lastAdjustmentMs == UNSET || nowMs - lastAdjustmentMs >= ADJUSTMENT_COOLDOWN_MS;
    }

    private Decision hold(float currentSpeed, Input input, Metrics metrics, String reason, boolean feasible) {
        return decision(state, tier, currentSpeed, false, reason, metrics, 0f, 0f, feasible);
    }

    private static Decision decision(State state, ProtectionTier tier, float targetSpeed, boolean changed, String reason, Metrics metrics,
                                     float requiredSlewPerSecond, float appliedSlewPerSecond, boolean rampFeasible) {
        return new Decision(state, tier, targetSpeed, changed, reason, metrics.supportedSpeed(), metrics.rawTargetSpeed(), metrics.calculatedTargetSpeed(),
                metrics.safeBufferMs(), metrics.timeToEmptyMs(), metrics.timeToReserveMs(), requiredSlewPerSecond, appliedSlewPerSecond, rampFeasible);
    }

    private static Metrics metrics(Input input, float currentSpeed, float minimumSpeed) {
        float supportedSpeed = supportedSpeed(currentSpeed, input);
        float rawTargetSpeed = supportedSpeed - SAFETY_MARGIN;
        float calculatedTargetSpeed = clamp(rawTargetSpeed, minimumSpeed, 1f);
        long safeBufferMs = Math.max(0, input.safeBufferMs());
        return new Metrics(supportedSpeed, rawTargetSpeed, calculatedTargetSpeed, safeBufferMs,
                timeToLevelMs(input.bufferedMs(), 0, input.slopeMsPerSecond()),
                timeToLevelMs(input.bufferedMs(), safeBufferMs, input.slopeMsPerSecond()));
    }

    private static float supportedSpeed(float currentSpeed, Input input) {
        if (!input.trendKnown()) return currentSpeed;
        float bufferSupported = currentSpeed + input.slopeMsPerSecond() / 1_000f;
        float supported = input.networkEstimateKnown() ? Math.min(bufferSupported, input.networkSupportedSpeed()) : bufferSupported;
        return clamp(supported, 0f, 2f);
    }

    private static long timeToLevelMs(long bufferedMs, long targetBufferMs, long slopeMsPerSecond) {
        long availableMs = bufferedMs - targetBufferMs;
        if (availableMs <= 0) return 0;
        if (slopeMsPerSecond >= 0) return Long.MAX_VALUE;
        if (availableMs > Long.MAX_VALUE / 1_000L) return Long.MAX_VALUE;
        return availableMs * 1_000L / -slopeMsPerSecond;
    }

    private static ProtectionTier tierForSpeed(float speed) {
        if (speed >= 1f - EPSILON) return ProtectionTier.NONE;
        return speed < ExoNetworkProtectionPolicy.PREFERRED_MIN_SPEED - EPSILON ? ProtectionTier.DEEP : ProtectionTier.LIGHT;
    }

    private static float roundThousandth(float value) {
        return Math.round(value * 1_000f) / 1_000f;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.min(Math.max(value, minimum), maximum);
    }

    public enum State {
        NORMAL("观察"),
        WARNING("评估"),
        PROTECT("保护"),
        RECOVERY("恢复"),
        UNSUSTAINABLE("保护能力不足");

        private final String text;

        State(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    public enum ProtectionTier {
        NONE(""),
        LIGHT("轻量"),
        DEEP("深度");

        private final String text;

        ProtectionTier(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    public record Input(long nowMs, boolean eligible, boolean ready, boolean playing, boolean loading, long bufferedMs, boolean trendKnown,
                        long slopeMsPerSecond, long fastSlopeMsPerSecond, long slowSlopeMsPerSecond, long trendWindowMs, int rebufferCount,
                        float currentSpeed, float minimumSpeed, long safeBufferMs, boolean networkEstimateKnown, float networkSupportedSpeed) {

        public Input(long nowMs, boolean eligible, boolean ready, boolean playing, boolean loading, long bufferedMs, boolean trendKnown,
                     long slopeMsPerSecond, long trendWindowMs, int rebufferCount, float currentSpeed, float minimumSpeed) {
            this(nowMs, eligible, ready, playing, loading, bufferedMs, trendKnown, slopeMsPerSecond, slopeMsPerSecond, slopeMsPerSecond,
                    trendWindowMs, rebufferCount, currentSpeed, minimumSpeed, 20_000, false, 1f);
        }
    }

    public record Decision(State state, ProtectionTier tier, float targetSpeed, boolean changed, String reason, float supportedSpeed,
                           float rawTargetSpeed, float calculatedTargetSpeed, long safeBufferMs, long timeToEmptyMs, long timeToReserveMs,
                           float requiredSlewPerSecond, float appliedSlewPerSecond, boolean rampFeasible) {
    }

    private record Metrics(float supportedSpeed, float rawTargetSpeed, float calculatedTargetSpeed, long safeBufferMs, long timeToEmptyMs, long timeToReserveMs) {

        private Metrics withCalculatedTarget(float value) {
            return new Metrics(supportedSpeed, rawTargetSpeed, value, safeBufferMs, timeToEmptyMs, timeToReserveMs);
        }
    }

    private record Ramp(float step, float requiredSlewPerSecond, float appliedSlewPerSecond, boolean feasible) {
    }
}
