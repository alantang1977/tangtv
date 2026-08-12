package com.fongmi.android.tv.player.exo;

import com.fongmi.android.tv.player.PlaybackAutoContext;

/** Pure automatic start/rebuffer policy. It never changes Media3 ABR or target bytes. */
final class ExoPlaybackThresholdPolicy {

    static final int MIN_STREAMING_START_MS = 500;
    static final int MAX_STREAMING_START_MS = 8_000;
    static final int MIN_STREAMING_REBUFFER_MS = 1_000;
    static final int MAX_STREAMING_REBUFFER_MS = 15_000;
    static final long THROUGHPUT_MAX_AGE_MS = 65_000L;
    static final long TREND_MAX_AGE_MS = 20_000L;
    static final long FULL_REBUFFER_HISTORY_MS = 30_000L;
    static final long DECAYED_REBUFFER_HISTORY_MS = 90_000L;

    private ExoPlaybackThresholdPolicy() {
    }

    static Decision resolve(Inputs input) {
        Inputs safe = input == null ? Inputs.unknown() : input;
        long now = Math.max(0, safe.nowElapsedMs());
        PlaybackAutoContext.Protocol protocol = usableValue(
                safe.resource().protocol(), PlaybackAutoContext.Protocol.UNKNOWN, now);
        PlaybackAutoContext.StreamKind streamKind = usableValue(
                safe.resource().streamKind(), PlaybackAutoContext.StreamKind.UNKNOWN, now);
        PlaybackAutoContext.TransferUnit transferUnit = usableValue(
                safe.resource().transferUnit(), PlaybackAutoContext.TransferUnit.UNKNOWN, now);
        PlaybackAutoContext.ManifestFacts manifest = usableManifest(
                safe.resource().manifest(), now);
        boolean lowLatency = streamKind == PlaybackAutoContext.StreamKind.LOW_LATENCY_LIVE
                || Boolean.TRUE.equals(manifest.lowLatency());
        boolean live = streamKind == PlaybackAutoContext.StreamKind.LIVE || lowLatency;
        boolean local = protocol == PlaybackAutoContext.Protocol.LOCAL
                || usableValue(
                safe.path().playerPath(), PlaybackAutoContext.PathKind.UNKNOWN, now)
                == PlaybackAutoContext.PathKind.LOCAL;
        int boundaryMs = boundaryMs(
                protocol, transferUnit, manifest, lowLatency);
        TrendThresholds trendThresholds = trendThresholds(
                boundaryMs,
                transferUnit,
                lowLatency,
                safe.configuredRebufferMs());

        if (local) {
            return new Decision(
                    500,
                    1_000,
                    protocol,
                    streamKind,
                    boundaryMs,
                    trendThresholds,
                    RiskLevel.NONE,
                    Reason.LOCAL_RESOURCE,
                    false,
                    false,
                    false,
                    true,
                    -1,
                    -1,
                    -1,
                    0,
                    false,
                    false);
        }

        ThresholdPair baseline = baselineThresholds(
                protocol,
                Math.clamp(
                        safe.configuredStartBufferMs(),
                        MIN_STREAMING_START_MS,
                        MAX_STREAMING_START_MS),
                Math.clamp(
                        safe.configuredRebufferMs(),
                        MIN_STREAMING_REBUFFER_MS,
                        MAX_STREAMING_REBUFFER_MS));
        boolean conservativePath = conservativePath(safe, now);
        if (conservativePath) {
            baseline = baseline.max(new ThresholdPair(3_000, 5_000));
        }

        ThroughputAssessment throughput = assessThroughput(safe, now);
        TrendAssessment trend = assessTrend(safe, trendThresholds, now);
        RiskAssessment risk = RiskAssessment.none();
        risk = risk.raise(historyRisk(safe), Reason.REBUFFER_HISTORY);
        risk = risk.raise(throughput.risk(), throughput.reason());
        risk = risk.raise(trend.risk(), trend.reason());

        boolean loweringEligible = !conservativePath
                && protocol != PlaybackAutoContext.Protocol.UNKNOWN
                && protocol != PlaybackAutoContext.Protocol.OTHER
                && throughput.stable()
                && stableBuffer(safe, trend, trendThresholds, live, manifest)
                && rebufferHistoryAllowsLowering(safe);
        ThresholdPair selected = baseline;
        Reason reason = conservativePath ? Reason.CONSERVATIVE_PATH : Reason.BASELINE;
        if (risk.level() != RiskLevel.NONE) {
            selected = selected.max(risk.level().thresholdFloor());
            reason = risk.reason();
            loweringEligible = false;
        } else if (loweringEligible) {
            ThresholdPair stable = stableThresholds(
                    protocol,
                    streamKind,
                    lowLatency,
                    boundaryMs,
                    safe,
                    now);
            selected = stable;
            reason = Reason.STABLE_RECOVERY;
        }

        boolean segmented = segmented(protocol, transferUnit);
        if (segmented && boundaryMs > 0) {
            selected = new ThresholdPair(
                    alignUp(selected.startMs(), boundaryMs, MAX_STREAMING_START_MS),
                    alignUp(selected.rebufferMs(), boundaryMs, MAX_STREAMING_REBUFFER_MS));
        }
        ThresholdPair beforeLiveCap = selected;
        selected = capLiveThresholds(
                selected,
                live,
                manifest.holdBackMs(),
                safe.targetLiveOffsetMs());
        boolean immediateDecrease = selected.startMs() < beforeLiveCap.startMs()
                || selected.rebufferMs() < beforeLiveCap.rebufferMs();

        return new Decision(
                selected.startMs(),
                selected.rebufferMs(),
                protocol,
                streamKind,
                boundaryMs,
                trendThresholds,
                risk.level(),
                reason,
                loweringEligible,
                conservativePath,
                segmented,
                immediateDecrease,
                throughput.ratioPermille(),
                throughput.predictionErrorPermille(),
                trend.timeToEmptyMs(),
                trend.slopeMsPerSecond(),
                throughput.usable(),
                trend.usable());
    }

    static int lowerOneStep(int currentMs, int targetMs) {
        int current = Math.max(0, currentMs);
        int target = Math.max(0, targetMs);
        if (target >= current) return current;
        int next = switch (current) {
            case 0 -> 0;
            default -> {
                if (current > 8_000) yield 8_000;
                if (current > 5_000) yield 5_000;
                if (current > 3_000) yield 3_000;
                if (current > 2_000) yield 2_000;
                if (current > 1_500) yield 1_500;
                if (current > 1_000) yield 1_000;
                yield 500;
            }
        };
        return Math.max(target, next);
    }

    private static ThresholdPair baselineThresholds(
            PlaybackAutoContext.Protocol protocol,
            int configuredStartMs,
            int configuredRebufferMs) {
        ThresholdPair configured = new ThresholdPair(
                configuredStartMs, configuredRebufferMs);
        return switch (protocol) {
            case PROGRESSIVE_HTTP -> configured.max(new ThresholdPair(1_500, 3_000));
            case HLS, DASH, RTSP, RTMP -> configured.max(new ThresholdPair(1_000, 2_000));
            default -> configured;
        };
    }

    private static ThresholdPair stableThresholds(
            PlaybackAutoContext.Protocol protocol,
            PlaybackAutoContext.StreamKind streamKind,
            boolean lowLatency,
            int boundaryMs,
            Inputs safe,
            long now) {
        if (lowLatency) {
            int unit = boundaryMs > 0 ? boundaryMs : 500;
            return new ThresholdPair(
                    Math.max(500, saturatingMultiply(unit, 2)),
                    Math.max(1_000, saturatingMultiply(unit, 3)));
        }
        if (streamKind == PlaybackAutoContext.StreamKind.LIVE) {
            return new ThresholdPair(1_000, 2_000);
        }
        if (protocol == PlaybackAutoContext.Protocol.HLS
                || protocol == PlaybackAutoContext.Protocol.DASH) {
            return new ThresholdPair(1_000, 2_000);
        }
        if (protocol == PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP) {
            PlaybackAutoContext.PathKind path = usableValue(
                    safe.path().playerPath(), PlaybackAutoContext.PathKind.UNKNOWN, now);
            return path == PlaybackAutoContext.PathKind.LAN_PRIVATE
                    ? new ThresholdPair(1_000, 1_500)
                    : new ThresholdPair(1_500, 3_000);
        }
        if (protocol == PlaybackAutoContext.Protocol.RTSP
                || protocol == PlaybackAutoContext.Protocol.RTMP) {
            return new ThresholdPair(1_000, 2_000);
        }
        return baselineThresholds(
                protocol,
                safe.configuredStartBufferMs(),
                safe.configuredRebufferMs());
    }

    private static RiskLevel historyRisk(Inputs safe) {
        int count = Math.max(0, safe.rebufferCount());
        long totalMs = Math.max(0, safe.rebufferTotalMs());
        long ageMs = safe.currentlyRebuffering()
                ? 0 : safe.lastRebufferAgeMs();
        if (count <= 0 && totalMs <= 0) return RiskLevel.NONE;
        if (ageMs < 0 || ageMs > DECAYED_REBUFFER_HISTORY_MS) {
            return RiskLevel.NONE;
        }
        if (ageMs > FULL_REBUFFER_HISTORY_MS) {
            return count >= 2 || totalMs >= 8_000
                    ? RiskLevel.GUARDED : RiskLevel.NONE;
        }
        if (count >= 5 || totalMs >= 30_000) return RiskLevel.CRITICAL;
        if (count >= 3 || totalMs >= 15_000) return RiskLevel.HIGH;
        if (count >= 2 || totalMs >= 8_000) return RiskLevel.CAUTION;
        return RiskLevel.GUARDED;
    }

    private static ThroughputAssessment assessThroughput(Inputs safe, long now) {
        ExoThroughputEstimator.Snapshot throughput = safe.throughput();
        if (throughput == null
                || !safe.session().active()
                || !safe.session().equals(throughput.session())
                || throughput.observedAtElapsedMs() < 0
                || now - throughput.observedAtElapsedMs() > THROUGHPUT_MAX_AGE_MS
                || throughput.effectiveEstimateBitsPerSecond() <= 0) {
            return ThroughputAssessment.unknown();
        }
        int error = throughput.predictionErrorPermille();
        long ratio = ratioPermille(
                throughput.effectiveEstimateBitsPerSecond(),
                safe.mediaBitrateBitsPerSecond());
        boolean mediaReliable = safe.mediaBitrateBitsPerSecond() > 0
                && confidenceAtLeast(
                safe.mediaBitrateConfidence(), PlaybackAutoContext.Confidence.MEDIUM);
        boolean controllable = throughput.pathTrust() != ExoThroughputPathPolicy.Trust.BLOCKED;
        RiskAssessment risk = RiskAssessment.none();
        if (mediaReliable && controllable && ratio >= 0) {
            if (ratio < 1_000) {
                risk = risk.raise(RiskLevel.CRITICAL, Reason.THROUGHPUT_DEFICIT);
            } else if (ratio < 1_150) {
                risk = risk.raise(RiskLevel.HIGH, Reason.THROUGHPUT_DEFICIT);
            } else if (ratio < 1_350) {
                risk = risk.raise(RiskLevel.CAUTION, Reason.THROUGHPUT_DEFICIT);
            }
        }
        boolean declining = throughput.action() == ExoThroughputEstimator.Action.DECREASE
                || throughput.reason() == ExoThroughputEstimator.Reason.FAST_DECREASE
                || materiallyBelow(
                throughput.shortEstimateBitsPerSecond(),
                throughput.longEstimateBitsPerSecond(),
                80);
        if (declining && controllable) {
            risk = risk.raise(
                    mediaReliable && ratio >= 0 && ratio < 1_500
                            ? RiskLevel.HIGH : RiskLevel.CAUTION,
                    Reason.NETWORK_VOLATILITY);
        }
        if (controllable) {
            if (error > 1_000) {
                risk = risk.raise(RiskLevel.HIGH, Reason.PREDICTION_ERROR);
            } else if (error > ExoThroughputEstimator.MAX_UPGRADE_ERROR_PERMILLE) {
                risk = risk.raise(RiskLevel.CAUTION, Reason.PREDICTION_ERROR);
            }
        }

        boolean stable = controllable
                && throughput.pathTrust() == ExoThroughputPathPolicy.Trust.TRUSTED
                && confidenceAtLeast(
                throughput.confidence(), PlaybackAutoContext.Confidence.MEDIUM)
                && mediaReliable
                && ratio >= 1_800
                && error >= 0
                && error <= 250
                && throughput.longSampleCount() >= ExoThroughputEstimator.MIN_UPGRADE_SAMPLES
                && throughput.longWindowMs() >= ExoThroughputEstimator.MIN_UPGRADE_WINDOW_MS
                && !throughput.preloadContended()
                && !declining;
        return new ThroughputAssessment(
                true,
                ratio,
                error,
                risk.level(),
                risk.reason(),
                stable);
    }

    private static TrendAssessment assessTrend(
            Inputs safe,
            TrendThresholds thresholds,
            long now) {
        ForwardBufferTrend.Snapshot trend = safe.trend();
        if (trend == null
                || !trend.known()
                || trend.sampledAtElapsedMs() < 0
                || now - trend.sampledAtElapsedMs() > TREND_MAX_AGE_MS
                || trend.windowMs() < thresholds.minimumWindowMs()) {
            return TrendAssessment.unknown();
        }
        long timeToEmptyMs = trend.timeToEmptyMs();
        if (timeToEmptyMs >= 0
                && timeToEmptyMs <= thresholds.criticalTimeToEmptyMs()) {
            return new TrendAssessment(
                    true,
                    trend.slopeMsPerSecond(),
                    timeToEmptyMs,
                    RiskLevel.CRITICAL,
                    Reason.TIME_TO_EMPTY);
        }
        if (timeToEmptyMs >= 0
                && timeToEmptyMs <= thresholds.warningTimeToEmptyMs()) {
            return new TrendAssessment(
                    true,
                    trend.slopeMsPerSecond(),
                    timeToEmptyMs,
                    RiskLevel.HIGH,
                    Reason.TIME_TO_EMPTY);
        }
        if (trend.slopeMsPerSecond() <= thresholds.drainSlopeMsPerSecond()) {
            return new TrendAssessment(
                    true,
                    trend.slopeMsPerSecond(),
                    timeToEmptyMs,
                    RiskLevel.CAUTION,
                    Reason.DRAINING_BUFFER);
        }
        return new TrendAssessment(
                true,
                trend.slopeMsPerSecond(),
                timeToEmptyMs,
                RiskLevel.NONE,
                Reason.BASELINE);
    }

    private static boolean stableBuffer(
            Inputs safe,
            TrendAssessment trend,
            TrendThresholds thresholds,
            boolean live,
            PlaybackAutoContext.ManifestFacts manifest) {
        if (trend.usable()
                && trend.slopeMsPerSecond() >= thresholds.stableSlopeMsPerSecond()) {
            return true;
        }
        long requiredMs = live
                ? Math.max(3_000, Math.min(10_000, thresholds.warningTimeToEmptyMs()))
                : Math.max(15_000, thresholds.warningTimeToEmptyMs());
        if (live && manifest.holdBackMs() != null && manifest.holdBackMs() > 0) {
            requiredMs = Math.min(requiredMs, manifest.holdBackMs());
        }
        return safe.bufferedDurationMs() >= requiredMs;
    }

    private static boolean rebufferHistoryAllowsLowering(Inputs safe) {
        return !safe.currentlyRebuffering()
                && (safe.lastRebufferAgeMs() < 0
                || safe.lastRebufferAgeMs() >= FULL_REBUFFER_HISTORY_MS);
    }

    private static TrendThresholds trendThresholds(
            int boundaryMs,
            PlaybackAutoContext.TransferUnit transferUnit,
            boolean lowLatency,
            int configuredRebufferMs) {
        int minimumWindowMs;
        if (boundaryMs > 0 && (lowLatency
                || transferUnit == PlaybackAutoContext.TransferUnit.PART)) {
            minimumWindowMs = Math.clamp(
                    saturatingMultiply(boundaryMs, 6), 5_000, 15_000);
        } else if (boundaryMs > 0) {
            minimumWindowMs = Math.clamp(
                    saturatingMultiply(boundaryMs, 3), 5_000, 30_000);
        } else {
            minimumWindowMs = 15_000;
        }
        int rebufferMs = Math.clamp(
                configuredRebufferMs,
                MIN_STREAMING_REBUFFER_MS,
                MAX_STREAMING_REBUFFER_MS);
        int warningMs = Math.clamp(
                Math.max(
                        saturatingMultiply(rebufferMs, 3),
                        saturatingMultiply(boundaryMs, 3)),
                10_000,
                30_000);
        int criticalMs = Math.clamp(
                Math.max(rebufferMs, saturatingMultiply(boundaryMs, 2)),
                5_000,
                15_000);
        int drainMagnitude = boundaryMs > 0
                ? Math.clamp(
                (int) Math.max(1L, (long) boundaryMs * 1_000L / minimumWindowMs),
                250,
                750)
                : 350;
        return new TrendThresholds(
                minimumWindowMs,
                warningMs,
                criticalMs,
                -drainMagnitude,
                100);
    }

    private static ThresholdPair capLiveThresholds(
            ThresholdPair selected,
            boolean live,
            Long holdBackMs,
            long targetLiveOffsetMs) {
        if (!live) return selected;
        int startCap = 5_000;
        int rebufferCap = 8_000;
        if (holdBackMs != null && holdBackMs > 0) {
            int holdBack = (int) Math.min(Integer.MAX_VALUE, holdBackMs);
            startCap = Math.min(startCap, Math.max(MIN_STREAMING_START_MS, holdBack));
            rebufferCap = Math.min(
                    rebufferCap,
                    Math.max(MIN_STREAMING_REBUFFER_MS, holdBack));
        }
        if (targetLiveOffsetMs > 0) {
            int halfOffset = (int) Math.min(
                    Integer.MAX_VALUE, targetLiveOffsetMs / 2L);
            startCap = Math.min(startCap, Math.max(MIN_STREAMING_START_MS, halfOffset));
            rebufferCap = Math.min(
                    rebufferCap,
                    Math.max(MIN_STREAMING_REBUFFER_MS, halfOffset));
        }
        return new ThresholdPair(
                Math.min(selected.startMs(), startCap),
                Math.min(selected.rebufferMs(), rebufferCap));
    }

    private static int boundaryMs(
            PlaybackAutoContext.Protocol protocol,
            PlaybackAutoContext.TransferUnit transferUnit,
            PlaybackAutoContext.ManifestFacts manifest,
            boolean lowLatency) {
        boolean segmented = segmented(protocol, transferUnit);
        if (!segmented) return 0;
        if ((lowLatency || transferUnit == PlaybackAutoContext.TransferUnit.PART)
                && validBoundary(manifest.partDurationMs())) {
            return (int) Math.min(Integer.MAX_VALUE, manifest.partDurationMs());
        }
        if (validBoundary(manifest.targetDurationMs())) {
            return (int) Math.min(Integer.MAX_VALUE, manifest.targetDurationMs());
        }
        return 0;
    }

    private static boolean segmented(
            PlaybackAutoContext.Protocol protocol,
            PlaybackAutoContext.TransferUnit transferUnit) {
        return protocol == PlaybackAutoContext.Protocol.HLS
                || protocol == PlaybackAutoContext.Protocol.DASH
                || transferUnit == PlaybackAutoContext.TransferUnit.SEGMENT
                || transferUnit == PlaybackAutoContext.TransferUnit.PART;
    }

    private static boolean conservativePath(Inputs safe, long now) {
        PlaybackAutoContext.PathKind path = usableValue(
                safe.path().playerPath(), PlaybackAutoContext.PathKind.UNKNOWN, now);
        PlaybackAutoContext.UpstreamState upstream = usableValue(
                safe.path().upstreamState(), PlaybackAutoContext.UpstreamState.UNKNOWN, now);
        boolean loopback = usableValue(safe.path().loopback(), false, now);
        return path == PlaybackAutoContext.PathKind.EXTERNAL_LOOPBACK
                || upstream == PlaybackAutoContext.UpstreamState.OPAQUE
                || loopback && path != PlaybackAutoContext.PathKind.APP_INTERNAL_SERVICE;
    }

    private static PlaybackAutoContext.ManifestFacts usableManifest(
            PlaybackAutoContext.Fact<PlaybackAutoContext.ManifestFacts> fact,
            long now) {
        return fact != null && fact.isUsable(now)
                ? fact.value() : PlaybackAutoContext.ManifestFacts.unknown();
    }

    private static <T> T usableValue(
            PlaybackAutoContext.Fact<T> fact,
            T fallback,
            long now) {
        return fact != null && fact.isUsable(now) ? fact.value() : fallback;
    }

    private static boolean validBoundary(Long value) {
        return value != null && value > 0 && value <= 60_000;
    }

    private static int alignUp(int valueMs, int boundaryMs, int maximumMs) {
        int value = Math.clamp(valueMs, 0, maximumMs);
        if (value <= 0 || boundaryMs <= 0) return value;
        long units = ((long) value + boundaryMs - 1L) / boundaryMs;
        long aligned = units * boundaryMs;
        return (int) Math.min(maximumMs, aligned);
    }

    private static int saturatingMultiply(int value, int multiplier) {
        if (value <= 0 || multiplier <= 0) return 0;
        return value > Integer.MAX_VALUE / multiplier
                ? Integer.MAX_VALUE : value * multiplier;
    }

    private static boolean materiallyBelow(long candidate, long reference, int percent) {
        if (candidate <= 0 || reference <= 0 || percent <= 0) return false;
        return (double) candidate * 100d < (double) reference * percent;
    }

    private static long ratioPermille(long numerator, long denominator) {
        if (numerator <= 0 || denominator <= 0) return -1;
        return Math.min(10_000L, (long) ((double) numerator * 1_000d / denominator));
    }

    private static boolean confidenceAtLeast(
            PlaybackAutoContext.Confidence actual,
            PlaybackAutoContext.Confidence required) {
        return confidenceRank(actual) >= confidenceRank(required);
    }

    private static int confidenceRank(PlaybackAutoContext.Confidence confidence) {
        if (confidence == null) return 0;
        return switch (confidence) {
            case UNKNOWN -> 0;
            case LOW -> 1;
            case MEDIUM -> 2;
            case HIGH -> 3;
        };
    }

    enum RiskLevel {
        NONE(new ThresholdPair(0, 0)),
        GUARDED(new ThresholdPair(1_500, 3_000)),
        CAUTION(new ThresholdPair(3_000, 5_000)),
        HIGH(new ThresholdPair(5_000, 8_000)),
        CRITICAL(new ThresholdPair(8_000, 15_000));

        private final ThresholdPair thresholdFloor;

        RiskLevel(ThresholdPair thresholdFloor) {
            this.thresholdFloor = thresholdFloor;
        }

        ThresholdPair thresholdFloor() {
            return thresholdFloor;
        }
    }

    enum Reason {
        LOCAL_RESOURCE("local-resource"),
        BASELINE("baseline"),
        CONSERVATIVE_PATH("conservative-path"),
        REBUFFER_HISTORY("rebuffer-history"),
        THROUGHPUT_DEFICIT("throughput-deficit"),
        NETWORK_VOLATILITY("network-volatility"),
        PREDICTION_ERROR("prediction-error"),
        TIME_TO_EMPTY("time-to-empty"),
        DRAINING_BUFFER("draining-buffer"),
        STABLE_RECOVERY("stable-recovery");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    record Inputs(
            PlaybackAutoContext.SessionToken session,
            int configuredStartBufferMs,
            int configuredRebufferMs,
            PlaybackAutoContext.ResourceFacts resource,
            PlaybackAutoContext.PathFacts path,
            ExoThroughputEstimator.Snapshot throughput,
            ForwardBufferTrend.Snapshot trend,
            long bufferedDurationMs,
            long mediaBitrateBitsPerSecond,
            PlaybackAutoContext.Confidence mediaBitrateConfidence,
            int rebufferCount,
            long rebufferTotalMs,
            boolean currentlyRebuffering,
            long lastRebufferAgeMs,
            long targetLiveOffsetMs,
            long nowElapsedMs) {

        Inputs {
            session = session == null
                    ? PlaybackAutoContext.SessionToken.none() : session;
            resource = resource == null
                    ? PlaybackAutoContext.ResourceFacts.unknown() : resource;
            path = path == null ? PlaybackAutoContext.PathFacts.unknown() : path;
            throughput = throughput == null
                    ? ExoThroughputEstimator.Snapshot.empty() : throughput;
            trend = trend == null ? ForwardBufferTrend.Snapshot.unknown() : trend;
            mediaBitrateConfidence = mediaBitrateConfidence == null
                    ? PlaybackAutoContext.Confidence.UNKNOWN : mediaBitrateConfidence;
            bufferedDurationMs = Math.max(0, bufferedDurationMs);
            mediaBitrateBitsPerSecond = Math.max(0, mediaBitrateBitsPerSecond);
            rebufferCount = Math.max(0, rebufferCount);
            rebufferTotalMs = Math.max(0, rebufferTotalMs);
            lastRebufferAgeMs = Math.max(-1, lastRebufferAgeMs);
            targetLiveOffsetMs = Math.max(-1, targetLiveOffsetMs);
            nowElapsedMs = Math.max(0, nowElapsedMs);
        }

        static Inputs unknown() {
            return new Inputs(
                    PlaybackAutoContext.SessionToken.none(),
                    1_500,
                    3_000,
                    PlaybackAutoContext.ResourceFacts.unknown(),
                    PlaybackAutoContext.PathFacts.unknown(),
                    ExoThroughputEstimator.Snapshot.empty(),
                    ForwardBufferTrend.Snapshot.unknown(),
                    0,
                    0,
                    PlaybackAutoContext.Confidence.UNKNOWN,
                    0,
                    0,
                    false,
                    -1,
                    -1,
                    0);
        }

        Inputs withRebufferHistory(
                int observedCount,
                boolean active,
                long ageMs) {
            return new Inputs(
                    session,
                    configuredStartBufferMs,
                    configuredRebufferMs,
                    resource,
                    path,
                    throughput,
                    trend,
                    bufferedDurationMs,
                    mediaBitrateBitsPerSecond,
                    mediaBitrateConfidence,
                    Math.max(rebufferCount, observedCount),
                    rebufferTotalMs,
                    active,
                    ageMs,
                    targetLiveOffsetMs,
                    nowElapsedMs);
        }
    }

    record TrendThresholds(
            int minimumWindowMs,
            int warningTimeToEmptyMs,
            int criticalTimeToEmptyMs,
            int drainSlopeMsPerSecond,
            int stableSlopeMsPerSecond) {
    }

    record Decision(
            int startBufferMs,
            int rebufferMs,
            PlaybackAutoContext.Protocol protocol,
            PlaybackAutoContext.StreamKind streamKind,
            int boundaryMs,
            TrendThresholds trendThresholds,
            RiskLevel riskLevel,
            Reason reason,
            boolean loweringEligible,
            boolean conservativePath,
            boolean segmented,
            boolean immediateDecrease,
            long throughputRatioPermille,
            int predictionErrorPermille,
            long timeToEmptyMs,
            long bufferSlopeMsPerSecond,
            boolean throughputUsable,
            boolean trendUsable) {

        Decision {
            startBufferMs = Math.clamp(
                    startBufferMs, MIN_STREAMING_START_MS, MAX_STREAMING_START_MS);
            rebufferMs = Math.clamp(
                    rebufferMs, MIN_STREAMING_REBUFFER_MS, MAX_STREAMING_REBUFFER_MS);
            protocol = protocol == null
                    ? PlaybackAutoContext.Protocol.UNKNOWN : protocol;
            streamKind = streamKind == null
                    ? PlaybackAutoContext.StreamKind.UNKNOWN : streamKind;
            trendThresholds = trendThresholds == null
                    ? ExoPlaybackThresholdPolicy.trendThresholds(
                    0,
                    PlaybackAutoContext.TransferUnit.UNKNOWN,
                    false,
                    3_000)
                    : trendThresholds;
            riskLevel = riskLevel == null ? RiskLevel.NONE : riskLevel;
            reason = reason == null ? Reason.BASELINE : reason;
        }
    }

    private record ThresholdPair(int startMs, int rebufferMs) {

        ThresholdPair {
            startMs = Math.max(0, startMs);
            rebufferMs = Math.max(0, rebufferMs);
        }

        ThresholdPair max(ThresholdPair other) {
            if (other == null) return this;
            return new ThresholdPair(
                    Math.max(startMs, other.startMs),
                    Math.max(rebufferMs, other.rebufferMs));
        }
    }

    private record RiskAssessment(RiskLevel level, Reason reason) {

        static RiskAssessment none() {
            return new RiskAssessment(RiskLevel.NONE, Reason.BASELINE);
        }

        RiskAssessment raise(RiskLevel candidate, Reason candidateReason) {
            RiskLevel safeCandidate = candidate == null ? RiskLevel.NONE : candidate;
            if (safeCandidate.ordinal() < level.ordinal()) return this;
            return new RiskAssessment(
                    safeCandidate,
                    candidateReason == null ? Reason.BASELINE : candidateReason);
        }
    }

    private record ThroughputAssessment(
            boolean usable,
            long ratioPermille,
            int predictionErrorPermille,
            RiskLevel risk,
            Reason reason,
            boolean stable) {

        static ThroughputAssessment unknown() {
            return new ThroughputAssessment(
                    false,
                    -1,
                    -1,
                    RiskLevel.NONE,
                    Reason.BASELINE,
                    false);
        }
    }

    private record TrendAssessment(
            boolean usable,
            long slopeMsPerSecond,
            long timeToEmptyMs,
            RiskLevel risk,
            Reason reason) {

        static TrendAssessment unknown() {
            return new TrendAssessment(
                    false,
                    0,
                    -1,
                    RiskLevel.NONE,
                    Reason.BASELINE);
        }
    }
}
