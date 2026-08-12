package com.fongmi.android.tv.player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Bounded, privacy-safe inputs shared by decision logs and runtime telemetry. */
public final class PlaybackTelemetry {

    private PlaybackTelemetry() {
    }

    public static String safeLabel(String value) {
        return DecisionInput.safeValue(value);
    }

    public record Metric<T>(T value, PlaybackAutoContext.ValueSource source,
                           PlaybackAutoContext.Confidence confidence) {

        public Metric {
            source = source == null ? PlaybackAutoContext.ValueSource.UNKNOWN : source;
            confidence = confidence == null ? PlaybackAutoContext.Confidence.UNKNOWN : confidence;
            if (value == null || source == PlaybackAutoContext.ValueSource.UNKNOWN
                    || confidence == PlaybackAutoContext.Confidence.UNKNOWN) {
                value = null;
                source = PlaybackAutoContext.ValueSource.UNKNOWN;
                confidence = PlaybackAutoContext.Confidence.UNKNOWN;
            }
        }

        public static <T> Metric<T> unknown() {
            return new Metric<>(null, PlaybackAutoContext.ValueSource.UNKNOWN,
                    PlaybackAutoContext.Confidence.UNKNOWN);
        }

        public static <T> Metric<T> of(T value, PlaybackAutoContext.ValueSource source,
                                       PlaybackAutoContext.Confidence confidence) {
            return new Metric<>(value, source, confidence);
        }

        public boolean known() {
            return value != null && source != PlaybackAutoContext.ValueSource.UNKNOWN
                    && confidence != PlaybackAutoContext.Confidence.UNKNOWN;
        }
    }

    public record RuntimeObservation(
            Metric<PlaybackAutoContext.PlaybackPhase> phase,
            Metric<Boolean> loading,
            Metric<Long> positionMs,
            Metric<Long> durationMs,
            Metric<Long> bufferedDurationMs,
            Metric<Long> bandwidthBitsPerSecond,
            Metric<Long> mediaBitrateBitsPerSecond,
            Metric<Float> renderedFrameRate,
            Metric<Long> droppedFrames,
            Metric<Integer> rebufferCount,
            Metric<Long> rebufferTotalMs,
            Metric<Long> firstFrameElapsedMs,
            Metric<Long> liveLagMs) {

        public RuntimeObservation {
            phase = phase == null ? Metric.unknown() : phase;
            loading = loading == null ? Metric.unknown() : loading;
            positionMs = positionMs == null ? Metric.unknown() : positionMs;
            durationMs = durationMs == null ? Metric.unknown() : durationMs;
            bufferedDurationMs = bufferedDurationMs == null ? Metric.unknown() : bufferedDurationMs;
            bandwidthBitsPerSecond = bandwidthBitsPerSecond == null ? Metric.unknown() : bandwidthBitsPerSecond;
            mediaBitrateBitsPerSecond = mediaBitrateBitsPerSecond == null ? Metric.unknown() : mediaBitrateBitsPerSecond;
            renderedFrameRate = renderedFrameRate == null ? Metric.unknown() : renderedFrameRate;
            droppedFrames = droppedFrames == null ? Metric.unknown() : droppedFrames;
            rebufferCount = rebufferCount == null ? Metric.unknown() : rebufferCount;
            rebufferTotalMs = rebufferTotalMs == null ? Metric.unknown() : rebufferTotalMs;
            firstFrameElapsedMs = firstFrameElapsedMs == null ? Metric.unknown() : firstFrameElapsedMs;
            liveLagMs = liveLagMs == null ? Metric.unknown() : liveLagMs;
        }

        public static RuntimeObservation unknown() {
            return new RuntimeObservation(Metric.unknown(), Metric.unknown(), Metric.unknown(), Metric.unknown(),
                    Metric.unknown(), Metric.unknown(), Metric.unknown(), Metric.unknown(), Metric.unknown(),
                    Metric.unknown(), Metric.unknown(), Metric.unknown(), Metric.unknown());
        }
    }

    public enum DecisionDomain {
        NETWORK_PROTECTION("network-protection"),
        THROUGHPUT("throughput"),
        MPV_OUTPUT("mpv-output"),
        MPV_CACHE("mpv-cache"),
        MPV_FORWARD_CACHE("mpv-forward-cache"),
        MPV_BACK_CACHE("mpv-back-cache"),
        MPV_RESOURCE_PRESSURE("mpv-resource-pressure"),
        MPV_HLS_VARIANT("mpv-hls-variant"),
        MPV_PRELOAD("mpv-preload"),
        IJK_BUFFER("ijk-buffer"),
        IJK_REALTIME_RECOVERY("ijk-realtime-recovery"),
        IJK_DECODE_PRESSURE("ijk-decode-pressure"),
        IJK_RUNTIME_PROFILE("ijk-runtime-profile"),
        DISPLAY_MODE("display-mode"),
        TUNNELING("tunneling"),
        LOAD_CONTROL("load-control"),
        PRELOAD("preload"),
        CACHE("cache"),
        DECODE("decode"),
        RTSP_LIVE_RECOVERY("rtsp-live-recovery"),
        OTHER("other");

        private final String label;

        DecisionDomain(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum DecisionOutcome {
        REQUESTED("requested"),
        APPLIED("applied"),
        SELECTED("selected"),
        HELD("held"),
        SUPPRESSED("suppressed"),
        FAILED("failed"),
        OBSERVED("observed");

        private final String label;

        DecisionOutcome(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record DecisionInput(String name, String value,
                                PlaybackAutoContext.ValueSource source,
                                PlaybackAutoContext.Confidence confidence) {

        public DecisionInput {
            name = safeName(name);
            value = safeValue(value);
            source = source == null ? PlaybackAutoContext.ValueSource.UNKNOWN : source;
            confidence = confidence == null ? PlaybackAutoContext.Confidence.UNKNOWN : confidence;
            if ("unknown".equals(name) || "unknown".equals(value)
                    || source == PlaybackAutoContext.ValueSource.UNKNOWN
                    || confidence == PlaybackAutoContext.Confidence.UNKNOWN) {
                value = "unknown";
                source = PlaybackAutoContext.ValueSource.UNKNOWN;
                confidence = PlaybackAutoContext.Confidence.UNKNOWN;
            }
        }

        public static DecisionInput text(String name, String value,
                                         PlaybackAutoContext.ValueSource source,
                                         PlaybackAutoContext.Confidence confidence) {
            return new DecisionInput(name, value, source, confidence);
        }

        public static DecisionInput number(String name, long value,
                                           PlaybackAutoContext.ValueSource source,
                                           PlaybackAutoContext.Confidence confidence) {
            return new DecisionInput(name, Long.toString(value), source, confidence);
        }

        public static DecisionInput decimal(String name, double value,
                                            PlaybackAutoContext.ValueSource source,
                                            PlaybackAutoContext.Confidence confidence) {
            if (!Double.isFinite(value)) return unknown(name);
            return new DecisionInput(name, String.format(Locale.US, "%.3f", value), source, confidence);
        }

        public static DecisionInput bool(String name, boolean value,
                                         PlaybackAutoContext.ValueSource source,
                                         PlaybackAutoContext.Confidence confidence) {
            return new DecisionInput(name, Boolean.toString(value), source, confidence);
        }

        public static DecisionInput unknown(String name) {
            return new DecisionInput(name, "unknown", PlaybackAutoContext.ValueSource.UNKNOWN,
                    PlaybackAutoContext.Confidence.UNKNOWN);
        }

        private static String safeName(String value) {
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.US);
            if (normalized.length() == 0 || normalized.length() > 48) return "unknown";
            for (int i = 0; i < normalized.length(); i++) {
                char c = normalized.charAt(i);
                if (Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-') continue;
                return "unknown";
            }
            return normalized;
        }

        private static String safeValue(String value) {
            String normalized = value == null ? "" : value.trim();
            if (normalized.length() == 0 || normalized.length() > 96
                    || normalized.contains("://") || normalized.contains("/")
                    || normalized.contains("\\") || normalized.contains("?")
                    || normalized.contains("@") || normalized.contains("=")
                    || normalized.contains("&") || normalized.contains("%")) return "unknown";
            for (int i = 0; i < normalized.length(); i++) {
                char c = normalized.charAt(i);
                if (Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-'
                        || c == '+' || c == ':' || c == ',' || c == ' ' || c == '(' || c == ')') continue;
                return "unknown";
            }
            return normalized;
        }
    }

    public record DecisionEvent(
            DecisionDomain domain,
            DecisionOutcome outcome,
            String oldValue,
            String targetValue,
            String resultValue,
            String reason,
            String suppressionReason,
            List<DecisionInput> inputs) {

        public DecisionEvent {
            domain = domain == null ? DecisionDomain.OTHER : domain;
            outcome = outcome == null ? DecisionOutcome.OBSERVED : outcome;
            oldValue = DecisionInput.safeValue(oldValue);
            targetValue = DecisionInput.safeValue(targetValue);
            resultValue = DecisionInput.safeValue(resultValue);
            reason = DecisionInput.safeValue(reason);
            suppressionReason = DecisionInput.safeValue(suppressionReason);
            List<DecisionInput> bounded = new ArrayList<>();
            if (inputs != null) {
                for (DecisionInput input : inputs) {
                    if (input != null && bounded.size() < 12) bounded.add(input);
                }
            }
            inputs = Collections.unmodifiableList(bounded);
        }

        public String semanticKey() {
            return domain.label() + "|" + outcome.label() + "|" + oldValue + "|" + targetValue
                    + "|" + resultValue + "|" + reason + "|" + suppressionReason;
        }
    }

    public record LogEntry(String tag, String traceId, String message) {
        public LogEntry {
            tag = tag == null || tag.isBlank() ? "playback-telemetry" : tag;
            traceId = PlaybackTrace.normalize(traceId);
            message = message == null ? "" : message;
        }
    }

    public record SessionSummary(
            String traceId,
            String reason,
            long durationMs,
            long decisionEvaluations,
            long decisionEmitted,
            long decisionSuppressed,
            long runtimeSamples,
            long runtimeLogs,
            long runtimeSuppressed,
            long peakBufferedMs,
            long maxDroppedFrames,
            int rebufferCount,
            long rebufferTotalMs) {
    }
}
