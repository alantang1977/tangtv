package com.fongmi.android.tv.player.exo;

import com.fongmi.android.tv.player.PlaybackAutoContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/** Bounded dual-window throughput model. It never selects tracks itself. */
final class ExoThroughputEstimator {

    static final long SHORT_WINDOW_MS = 12_000L;
    static final long LONG_WINDOW_MS = 60_000L;
    static final int MAX_SAMPLES = 64;
    static final long MIN_SAMPLE_BYTES = 32L * 1024L;
    static final int MIN_SAMPLE_ELAPSED_MS = 100;
    static final long MIN_VALID_BITRATE = 64_000L;
    static final long MAX_VALID_BITRATE = 5_000_000_000L;
    static final int MIN_UPGRADE_SAMPLES = 4;
    static final long MIN_UPGRADE_WINDOW_MS = 15_000L;
    static final long UPGRADE_INTERVAL_MS = 5_000L;
    static final int UPGRADE_STEP_PERCENT = 15;
    static final int MAX_UPGRADE_ERROR_PERMILLE = 500;

    private final Deque<Sample> samples = new ArrayDeque<>();
    private final Deque<ErrorSample> predictionErrors = new ArrayDeque<>();
    private Snapshot snapshot = Snapshot.empty();
    private long lastObservationMs = -1;
    private long lastAcceptedMs = -1;
    private long upgradeEpochMs = -1;
    private long lastIncreaseMs = -1;

    synchronized Snapshot snapshot() {
        return snapshot;
    }

    synchronized Snapshot reset(
            PlaybackAutoContext.SessionToken session,
            long nowElapsedMs,
            long rawEstimateBitsPerSecond,
            Reason reason) {
        return reset(
                session,
                nowElapsedMs,
                rawEstimateBitsPerSecond,
                rawEstimateBitsPerSecond,
                reason);
    }

    synchronized Snapshot reset(
            PlaybackAutoContext.SessionToken session,
            long nowElapsedMs,
            long rawEstimateBitsPerSecond,
            long effectivePriorBitsPerSecond,
            Reason reason) {
        PlaybackAutoContext.SessionToken safeSession = session == null
                ? PlaybackAutoContext.SessionToken.none() : session;
        long now = Math.max(0, nowElapsedMs);
        long raw = positive(rawEstimateBitsPerSecond);
        long effective = positive(effectivePriorBitsPerSecond);
        if (effective <= 0) effective = raw;
        samples.clear();
        predictionErrors.clear();
        lastObservationMs = -1;
        lastAcceptedMs = -1;
        upgradeEpochMs = now;
        lastIncreaseMs = now;
        snapshot = new Snapshot(
                safeSession,
                raw,
                0,
                0,
                effective,
                0,
                0,
                0,
                0,
                0,
                -1,
                effective > 0 ? PlaybackAutoContext.Confidence.LOW
                        : PlaybackAutoContext.Confidence.UNKNOWN,
                ExoThroughputPathPolicy.Trust.LIMITED,
                PlaybackAutoContext.Confidence.UNKNOWN,
                false,
                0,
                effective > 0 ? Action.PRIOR : Action.RESET,
                reason == null ? Reason.SESSION_RESET : reason,
                now);
        return snapshot;
    }

    synchronized Update observe(
            PlaybackAutoContext.SessionToken session,
            long nowElapsedMs,
            long bytesTransferred,
            int elapsedMs,
            long rawEstimateBitsPerSecond,
            ExoThroughputPathPolicy.Decision pathDecision,
            boolean preloadContended,
            boolean fullNetworkSpeed) {
        PlaybackAutoContext.SessionToken safeSession = session == null
                ? PlaybackAutoContext.SessionToken.none() : session;
        Snapshot previous = snapshot;
        if (!safeSession.active() || !safeSession.equals(previous.session())) {
            return new Update(previous, previous, false, Reason.STALE_SESSION);
        }
        long now = Math.max(0, nowElapsedMs);
        if (lastObservationMs >= 0 && now < lastObservationMs) {
            return new Update(previous, previous, false, Reason.OUT_OF_ORDER);
        }
        lastObservationMs = now;

        ExoThroughputPathPolicy.Decision path = pathDecision == null
                ? new ExoThroughputPathPolicy.Decision(
                ExoThroughputPathPolicy.Trust.LIMITED,
                PlaybackAutoContext.Confidence.UNKNOWN,
                ExoThroughputPathPolicy.Reason.PATH_UNKNOWN)
                : pathDecision;
        long raw = positive(rawEstimateBitsPerSecond);
        if (!fullNetworkSpeed) {
            return hold(previous, raw, path, preloadContended, now,
                    Reason.NOT_FULL_NETWORK_SPEED, false);
        }
        if (!path.downgradeEligible()) {
            breakUpgradeContinuity(now);
            return hold(previous, raw, path, preloadContended, now,
                    Reason.PATH_BLOCKED, false);
        }
        if (bytesTransferred < MIN_SAMPLE_BYTES || elapsedMs < MIN_SAMPLE_ELAPSED_MS) {
            return hold(previous, raw, path, preloadContended, now,
                    Reason.SAMPLE_TOO_SHORT, false);
        }

        long sampleRate = rateFor(bytesTransferred, elapsedMs);
        if (sampleRate < MIN_VALID_BITRATE || sampleRate > MAX_VALID_BITRATE) {
            return hold(previous, raw, path, preloadContended, now,
                    Reason.SAMPLE_OUT_OF_RANGE, false);
        }

        boolean upgradeEligible = path.upgradeEligible() && !preloadContended;
        if (!upgradeEligible) breakUpgradeContinuity(now);
        long priorPrediction = previous.shortEstimateBitsPerSecond() > 0
                ? previous.shortEstimateBitsPerSecond()
                : previous.effectiveEstimateBitsPerSecond();
        recordPredictionError(priorPrediction, sampleRate,
                now, upgradeEligible);
        samples.addLast(new Sample(now, elapsedMs, sampleRate, upgradeEligible));
        lastAcceptedMs = now;
        trim(now);

        Stats shortStats = summarize(now, SHORT_WINDOW_MS, false);
        Stats longStats = summarize(now, LONG_WINDOW_MS, true);
        int predictionErrorPermille = predictionErrorPermille(now);
        long current = previous.effectiveEstimateBitsPerSecond() > 0
                ? previous.effectiveEstimateBitsPerSecond()
                : raw > 0 ? raw : sampleRate;
        long effective = current;
        Action action = Action.HOLD;
        Reason reason = holdReason(path, preloadContended, longStats,
                predictionErrorPermille);

        long decreaseCandidate = shortStats.estimateBitsPerSecond();
        if (raw > 0 && raw < decreaseCandidate) decreaseCandidate = raw;
        if (decreaseCandidate > 0 && materiallyLower(decreaseCandidate, current)) {
            if (shortStats.sampleCount() > 1
                    || decreaseCandidate <= percent(current, 70)) {
                effective = decreaseCandidate;
                action = Action.DECREASE;
                reason = Reason.FAST_DECREASE;
                breakUpgradeContinuity(now);
                longStats = summarize(now, LONG_WINDOW_MS, true);
                predictionErrorPermille = predictionErrorPermille(now);
            } else {
                reason = Reason.SINGLE_LOW_SAMPLE;
            }
        } else if (canIncrease(path, preloadContended, longStats,
                predictionErrorPermille, raw, current, now)) {
            long candidate = Math.min(longStats.estimateBitsPerSecond(), raw);
            long stepLimit = safeAdd(current,
                    Math.max(1, percent(current, UPGRADE_STEP_PERCENT)));
            effective = Math.min(candidate, stepLimit);
            if (materiallyHigher(effective, current)) {
                action = Action.INCREASE;
                reason = Reason.CAUTIOUS_INCREASE;
                lastIncreaseMs = now;
            } else {
                effective = current;
            }
        }

        PlaybackAutoContext.Confidence confidence = confidence(
                path, longStats, predictionErrorPermille, !samples.isEmpty());
        snapshot = new Snapshot(
                safeSession,
                raw,
                shortStats.estimateBitsPerSecond(),
                longStats.estimateBitsPerSecond(),
                effective,
                samples.size(),
                shortStats.sampleCount(),
                longStats.sampleCount(),
                shortStats.windowMs(),
                longStats.windowMs(),
                predictionErrorPermille,
                confidence,
                path.trust(),
                path.confidence(),
                preloadContended,
                sampleRate,
                action,
                reason,
                now);
        return new Update(previous, snapshot, true, reason);
    }

    private Update hold(
            Snapshot previous,
            long raw,
            ExoThroughputPathPolicy.Decision path,
            boolean preloadContended,
            long now,
            Reason reason,
            boolean accepted) {
        trim(now);
        Stats shortStats = summarize(now, SHORT_WINDOW_MS, false);
        Stats longStats = summarize(now, LONG_WINDOW_MS, true);
        int predictionErrorPermille = predictionErrorPermille(now);
        long effective = previous.effectiveEstimateBitsPerSecond();
        Action action = Action.HOLD;
        Reason resolvedReason = reason;
        if (lastAcceptedMs >= 0 && now - lastAcceptedMs > LONG_WINDOW_MS) {
            breakUpgradeContinuity(now);
            if (raw > 0 && (effective <= 0 || raw < effective)) {
                effective = raw;
                action = Action.DECREASE;
            }
            resolvedReason = Reason.SAMPLES_EXPIRED;
            shortStats = Stats.empty();
            longStats = Stats.empty();
            predictionErrorPermille = -1;
        }
        snapshot = new Snapshot(
                previous.session(),
                raw > 0 ? raw : previous.rawEstimateBitsPerSecond(),
                shortStats.estimateBitsPerSecond(),
                longStats.estimateBitsPerSecond(),
                effective,
                samples.size(),
                shortStats.sampleCount(),
                longStats.sampleCount(),
                shortStats.windowMs(),
                longStats.windowMs(),
                predictionErrorPermille,
                effective > 0
                        ? PlaybackAutoContext.Confidence.LOW
                        : PlaybackAutoContext.Confidence.UNKNOWN,
                path.trust(),
                path.confidence(),
                preloadContended,
                0,
                action,
                resolvedReason,
                now);
        return new Update(previous, snapshot, accepted, resolvedReason);
    }

    private boolean canIncrease(
            ExoThroughputPathPolicy.Decision path,
            boolean preloadContended,
            Stats longStats,
            int predictionErrorPermille,
            long raw,
            long current,
            long now) {
        if (!path.upgradeEligible() || preloadContended) return false;
        if (longStats.sampleCount() < MIN_UPGRADE_SAMPLES
                || longStats.windowMs() < MIN_UPGRADE_WINDOW_MS) return false;
        if (predictionErrorPermille < 0
                || predictionErrorPermille > MAX_UPGRADE_ERROR_PERMILLE) return false;
        if (raw <= current || longStats.estimateBitsPerSecond() <= current) return false;
        return lastIncreaseMs < 0 || now - lastIncreaseMs >= UPGRADE_INTERVAL_MS;
    }

    private static Reason holdReason(
            ExoThroughputPathPolicy.Decision path,
            boolean preloadContended,
            Stats longStats,
            int predictionErrorPermille) {
        if (preloadContended) return Reason.PRELOAD_CONTENTION;
        if (!path.upgradeEligible()) return Reason.LOW_PATH_CONFIDENCE;
        if (longStats.sampleCount() < MIN_UPGRADE_SAMPLES
                || longStats.windowMs() < MIN_UPGRADE_WINDOW_MS) {
            return Reason.INSUFFICIENT_LONG_WINDOW;
        }
        if (predictionErrorPermille < 0
                || predictionErrorPermille > MAX_UPGRADE_ERROR_PERMILLE) {
            return Reason.HIGH_PREDICTION_ERROR;
        }
        return Reason.STABLE_HOLD;
    }

    private static PlaybackAutoContext.Confidence confidence(
            ExoThroughputPathPolicy.Decision path,
            Stats longStats,
            int predictionErrorPermille,
            boolean hasSamples) {
        if (!hasSamples) return PlaybackAutoContext.Confidence.UNKNOWN;
        if (!path.upgradeEligible()) return PlaybackAutoContext.Confidence.LOW;
        if (longStats.sampleCount() >= 5 && longStats.windowMs() >= 30_000
                && predictionErrorPermille >= 0 && predictionErrorPermille <= 250) {
            return PlaybackAutoContext.Confidence.HIGH;
        }
        if (longStats.sampleCount() >= 3 && longStats.windowMs() >= 10_000) {
            return PlaybackAutoContext.Confidence.MEDIUM;
        }
        return PlaybackAutoContext.Confidence.LOW;
    }

    private void recordPredictionError(long prediction, long observed, long now,
                                       boolean upgradeEligible) {
        if (!upgradeEligible || prediction <= 0 || observed <= 0) return;
        long delta = prediction >= observed ? prediction - observed : observed - prediction;
        long permille = delta > Long.MAX_VALUE / 1_000L
                ? 4_000L : delta * 1_000L / observed;
        predictionErrors.addLast(new ErrorSample(now, (int) Math.min(4_000L, permille)));
    }

    private int predictionErrorPermille(long now) {
        trimErrors(now);
        if (predictionErrors.isEmpty()) return -1;
        List<Integer> errors = new ArrayList<>(predictionErrors.size());
        for (ErrorSample sample : predictionErrors) {
            if (sample.atElapsedMs() > upgradeEpochMs) errors.add(sample.permille());
        }
        if (errors.isEmpty()) return -1;
        Collections.sort(errors);
        int index = (int) Math.ceil(errors.size() * 0.50d) - 1;
        return errors.get(Math.clamp(index, 0, errors.size() - 1));
    }

    private Stats summarize(long now, long windowMs, boolean upgradeOnly) {
        long floor = Math.max(0, now - windowMs);
        long weightedDuration = 0;
        double reciprocal = 0d;
        int count = 0;
        long firstAt = -1;
        long lastAt = -1;
        int lastElapsed = 0;
        for (Sample sample : samples) {
            if (sample.atElapsedMs() < floor) continue;
            if (upgradeOnly && (!sample.upgradeEligible()
                    || sample.atElapsedMs() <= upgradeEpochMs)) continue;
            long weight = Math.clamp(sample.elapsedMs(), 1, 5_000);
            weightedDuration = safeAdd(weightedDuration, weight);
            reciprocal += (double) weight / sample.bitsPerSecond();
            count++;
            if (firstAt < 0) firstAt = sample.atElapsedMs();
            lastAt = sample.atElapsedMs();
            lastElapsed = sample.elapsedMs();
        }
        if (count == 0 || weightedDuration <= 0 || reciprocal <= 0d) {
            return Stats.empty();
        }
        long estimate = (long) Math.min(MAX_VALID_BITRATE,
                Math.max(0d, weightedDuration / reciprocal));
        long span = lastAt < firstAt ? 0
                : safeAdd(lastAt - firstAt, Math.max(0, lastElapsed));
        return new Stats(estimate, count, Math.min(windowMs, span));
    }

    private void trim(long now) {
        long floor = Math.max(0, now - LONG_WINDOW_MS);
        while (!samples.isEmpty() && samples.peekFirst().atElapsedMs() < floor) {
            samples.removeFirst();
        }
        while (samples.size() > MAX_SAMPLES) samples.removeFirst();
        trimErrors(now);
    }

    private void trimErrors(long now) {
        long floor = Math.max(0, now - LONG_WINDOW_MS);
        while (!predictionErrors.isEmpty()
                && predictionErrors.peekFirst().atElapsedMs() < floor) {
            predictionErrors.removeFirst();
        }
        while (predictionErrors.size() > MAX_SAMPLES) predictionErrors.removeFirst();
    }

    private void breakUpgradeContinuity(long now) {
        upgradeEpochMs = Math.max(upgradeEpochMs, now);
        predictionErrors.clear();
    }

    private static boolean materiallyLower(long candidate, long current) {
        return current > 0 && candidate > 0 && candidate <= percent(current, 95);
    }

    private static boolean materiallyHigher(long candidate, long current) {
        return current <= 0 ? candidate > 0 : candidate >= safeAdd(current,
                Math.max(1, percent(current, 5)));
    }

    private static long rateFor(long bytes, int elapsedMs) {
        if (bytes <= 0 || elapsedMs <= 0) return 0;
        if (bytes > Long.MAX_VALUE / 8_000L) return Long.MAX_VALUE;
        return bytes * 8_000L / elapsedMs;
    }

    private static long percent(long value, int percent) {
        if (value <= 0 || percent <= 0) return 0;
        return value > Long.MAX_VALUE / percent
                ? Long.MAX_VALUE / 100L : value * percent / 100L;
    }

    private static long safeAdd(long first, long second) {
        if (first <= 0) return Math.max(0, second);
        if (second <= 0) return first;
        return first > Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second;
    }

    private static long positive(long value) {
        return Math.max(0, value);
    }

    enum Action {
        RESET("reset"),
        PRIOR("prior"),
        HOLD("hold"),
        DECREASE("decrease"),
        INCREASE("increase");

        private final String label;

        Action(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    enum Reason {
        SESSION_RESET("session-reset"),
        NETWORK_RESET("network-reset"),
        NETWORK_CHANGED_DURING_SAMPLE("network-changed-during-sample"),
        STALE_SESSION("stale-session"),
        OUT_OF_ORDER("out-of-order"),
        NOT_FULL_NETWORK_SPEED("not-full-network-speed"),
        PATH_BLOCKED("path-blocked"),
        SAMPLE_TOO_SHORT("sample-too-short"),
        SAMPLE_OUT_OF_RANGE("sample-out-of-range"),
        SAMPLES_EXPIRED("samples-expired"),
        FAST_DECREASE("fast-decrease"),
        SINGLE_LOW_SAMPLE("single-low-sample"),
        CAUTIOUS_INCREASE("cautious-increase"),
        PRELOAD_CONTENTION("preload-contention"),
        LOW_PATH_CONFIDENCE("low-path-confidence"),
        INSUFFICIENT_LONG_WINDOW("insufficient-long-window"),
        HIGH_PREDICTION_ERROR("high-prediction-error"),
        STABLE_HOLD("stable-hold");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    record Snapshot(
            PlaybackAutoContext.SessionToken session,
            long rawEstimateBitsPerSecond,
            long shortEstimateBitsPerSecond,
            long longEstimateBitsPerSecond,
            long effectiveEstimateBitsPerSecond,
            int sampleCount,
            int shortSampleCount,
            int longSampleCount,
            long shortWindowMs,
            long longWindowMs,
            int predictionErrorPermille,
            PlaybackAutoContext.Confidence confidence,
            ExoThroughputPathPolicy.Trust pathTrust,
            PlaybackAutoContext.Confidence pathConfidence,
            boolean preloadContended,
            long lastSampleBitsPerSecond,
            Action action,
            Reason reason,
            long observedAtElapsedMs) {

        Snapshot {
            session = session == null ? PlaybackAutoContext.SessionToken.none() : session;
            confidence = confidence == null
                    ? PlaybackAutoContext.Confidence.UNKNOWN : confidence;
            pathTrust = pathTrust == null
                    ? ExoThroughputPathPolicy.Trust.LIMITED : pathTrust;
            pathConfidence = pathConfidence == null
                    ? PlaybackAutoContext.Confidence.UNKNOWN : pathConfidence;
            action = action == null ? Action.RESET : action;
            reason = reason == null ? Reason.SESSION_RESET : reason;
        }

        static Snapshot empty() {
            return new Snapshot(
                    PlaybackAutoContext.SessionToken.none(),
                    0, 0, 0, 0,
                    0, 0, 0, 0, 0, -1,
                    PlaybackAutoContext.Confidence.UNKNOWN,
                    ExoThroughputPathPolicy.Trust.LIMITED,
                    PlaybackAutoContext.Confidence.UNKNOWN,
                    false, 0, Action.RESET, Reason.SESSION_RESET, -1);
        }
    }

    record Update(
            Snapshot previous,
            Snapshot snapshot,
            boolean accepted,
            Reason reason) {
    }

    private record Sample(
            long atElapsedMs,
            int elapsedMs,
            long bitsPerSecond,
            boolean upgradeEligible) {
    }

    private record ErrorSample(long atElapsedMs, int permille) {
    }

    private record Stats(long estimateBitsPerSecond, int sampleCount, long windowMs) {

        private static Stats empty() {
            return new Stats(0, 0, 0);
        }
    }
}
