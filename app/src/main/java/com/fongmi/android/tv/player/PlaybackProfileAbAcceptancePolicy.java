package com.fongmi.android.tv.player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Conservative per-group P50/P95 profile-comparison acceptance policy. */
public final class PlaybackProfileAbAcceptancePolicy {

    public static final int MIN_SAMPLES_PER_ARM = 20;
    private static final long RATE_SCALE = 1_000_000L;

    private PlaybackProfileAbAcceptancePolicy() {
    }

    public static Report evaluate(PlaybackProfileAbStore.Snapshot snapshot) {
        return evaluate(
                snapshot, PlaybackProfileAbPolicy.Arm.RECOMMENDED);
    }

    public static Report evaluate(
            PlaybackProfileAbStore.Snapshot snapshot,
            PlaybackProfileAbPolicy.Arm comparisonArm) {
        PlaybackProfileAbPolicy.Arm comparison = comparisonArm == null
                || comparisonArm == PlaybackProfileAbPolicy.Arm.AUTO
                ? PlaybackProfileAbPolicy.Arm.RECOMMENDED
                : comparisonArm;
        PlaybackProfileAbStore.Snapshot safe = snapshot == null
                ? new PlaybackProfileAbStore.Snapshot(List.of()) : snapshot;
        if (safe.groups().isEmpty()) {
            return new Report(
                    Status.NO_DATA,
                    comparison,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    List.of());
        }
        List<GroupReport> groups = new ArrayList<>();
        int passed = 0;
        int failed = 0;
        int insufficient = 0;
        int missing = 0;
        for (PlaybackProfileAbStore.GroupSamples group : safe.groups()) {
            GroupReport report = evaluateGroup(group, comparison);
            groups.add(report);
            switch (report.status()) {
                case PASSED -> passed++;
                case FAILED -> failed++;
                case METRICS_MISSING -> missing++;
                case INSUFFICIENT_SAMPLES -> insufficient++;
                case NO_DATA -> insufficient++;
            }
        }
        Status status = failed > 0
                ? Status.FAILED
                : missing > 0
                ? Status.METRICS_MISSING
                : insufficient > 0
                ? Status.INSUFFICIENT_SAMPLES
                : Status.PASSED;
        return new Report(
                status,
                comparison,
                safe.automaticSampleCount(),
                safe.sampleCount(comparison),
                groups.size(),
                passed + failed,
                passed,
                failed,
                insufficient + missing,
                List.copyOf(groups));
    }

    private static GroupReport evaluateGroup(
            PlaybackProfileAbStore.GroupSamples group,
            PlaybackProfileAbPolicy.Arm comparisonArm) {
        if (group == null || group.groupKey() == null
                || !group.groupKey().valid()) {
            return new GroupReport(
                    null,
                    Status.NO_DATA,
                    0,
                    0,
                    Map.of());
        }
        List<PlaybackProfileAbStore.Sample> automatic = group.automatic();
        List<PlaybackProfileAbStore.Sample> recommended =
                group.samples(comparisonArm);
        if (automatic.size() < MIN_SAMPLES_PER_ARM
                || recommended.size() < MIN_SAMPLES_PER_ARM) {
            return new GroupReport(
                    group.groupKey(),
                    Status.INSUFFICIENT_SAMPLES,
                    automatic.size(),
                    recommended.size(),
                    Map.of());
        }
        EnumMap<Metric, MetricComparison> comparisons =
                new EnumMap<>(Metric.class);
        boolean missing = false;
        boolean failed = false;
        for (Metric metric : Metric.values()) {
            if (metric == Metric.LIVE_LAG_MS
                    && !group.groupKey().live()) continue;
            MetricComparison comparison = metric == Metric.ERROR_RATE_PPM
                    ? compareErrorRate(automatic, recommended)
                    : comparePercentiles(metric, automatic, recommended);
            comparisons.put(metric, comparison);
            if (!comparison.available()) missing = true;
            else if (!comparison.passed()) failed = true;
        }
        Status status = failed
                ? Status.FAILED
                : missing ? Status.METRICS_MISSING : Status.PASSED;
        return new GroupReport(
                group.groupKey(),
                status,
                automatic.size(),
                recommended.size(),
                Collections.unmodifiableMap(comparisons));
    }

    private static MetricComparison comparePercentiles(
            Metric metric,
            List<PlaybackProfileAbStore.Sample> automatic,
            List<PlaybackProfileAbStore.Sample> recommended) {
        List<Long> automaticValues = values(metric, automatic);
        List<Long> recommendedValues = values(metric, recommended);
        if (automaticValues.size() < MIN_SAMPLES_PER_ARM
                || recommendedValues.size() < MIN_SAMPLES_PER_ARM) {
            return MetricComparison.missing(
                    automaticValues.size(), recommendedValues.size());
        }
        long automaticP50 = percentile(automaticValues, 50);
        long automaticP95 = percentile(automaticValues, 95);
        long recommendedP50 = percentile(recommendedValues, 50);
        long recommendedP95 = percentile(recommendedValues, 95);
        boolean p50Passed = automaticP50 <= upperBound(
                recommendedP50,
                metric.p50RelativeTolerancePermille,
                metric.p50AbsoluteTolerance);
        boolean p95Passed = automaticP95 <= upperBound(
                recommendedP95,
                metric.p95RelativeTolerancePermille,
                metric.p95AbsoluteTolerance);
        return new MetricComparison(
                true,
                p50Passed && p95Passed,
                automaticValues.size(),
                recommendedValues.size(),
                automaticP50,
                automaticP95,
                recommendedP50,
                recommendedP95);
    }

    private static MetricComparison compareErrorRate(
            List<PlaybackProfileAbStore.Sample> automatic,
            List<PlaybackProfileAbStore.Sample> recommended) {
        if (automatic.size() < MIN_SAMPLES_PER_ARM
                || recommended.size() < MIN_SAMPLES_PER_ARM) {
            return MetricComparison.missing(
                    automatic.size(), recommended.size());
        }
        long automaticRate = rate(
                countErrors(automatic), automatic.size(), RATE_SCALE);
        long recommendedRate = rate(
                countErrors(recommended), recommended.size(), RATE_SCALE);
        boolean passed = automaticRate <= upperBound(
                recommendedRate,
                Metric.ERROR_RATE_PPM.p50RelativeTolerancePermille,
                Metric.ERROR_RATE_PPM.p50AbsoluteTolerance);
        return new MetricComparison(
                true,
                passed,
                automatic.size(),
                recommended.size(),
                automaticRate,
                automaticRate,
                recommendedRate,
                recommendedRate);
    }

    private static List<Long> values(
            Metric metric,
            List<PlaybackProfileAbStore.Sample> samples) {
        List<Long> values = new ArrayList<>();
        if (samples == null) return values;
        for (PlaybackProfileAbStore.Sample sample : samples) {
            Long value = value(metric, sample);
            if (value != null) values.add(value);
        }
        Collections.sort(values);
        return values;
    }

    private static Long value(
            Metric metric,
            PlaybackProfileAbStore.Sample sample) {
        if (sample == null) return null;
        return switch (metric) {
            case FIRST_FRAME_MS -> known(sample.firstFrameMs());
            case REBUFFER_RATIO_PPM -> sample.rebufferTotalMs() < 0
                    || sample.activePlaybackMs() <= 0
                    ? null
                    : rate(sample.rebufferTotalMs(),
                    sample.activePlaybackMs(), RATE_SCALE);
            case REBUFFER_COUNT -> sample.rebufferCount() < 0
                    || sample.firstFrameMs() < 0
                    ? null : (long) sample.rebufferCount();
            case PEAK_PSS_BYTES -> known(sample.peakPssBytes());
            case DROPPED_FRAMES_PER_MINUTE_MILLI ->
                    sample.maxDroppedFrames() < 0
                            || sample.activePlaybackMs() <= 0
                            ? null
                            : rate(sample.maxDroppedFrames(),
                            sample.activePlaybackMs(), 60_000_000L);
            case LIVE_LAG_MS -> known(sample.maxLiveLagMs());
            case ERROR_RATE_PPM -> null;
        };
    }

    private static Long known(long value) {
        return value < 0 ? null : value;
    }

    private static int countErrors(
            List<PlaybackProfileAbStore.Sample> samples) {
        int count = 0;
        for (PlaybackProfileAbStore.Sample sample : samples) {
            if (sample.errorObserved()) count++;
        }
        return count;
    }

    private static long percentile(List<Long> sorted, int percentile) {
        if (sorted == null || sorted.isEmpty()) return -1;
        int rank = (int) Math.ceil(
                percentile / 100.0d * sorted.size());
        int index = Math.max(0, Math.min(sorted.size() - 1, rank - 1));
        return sorted.get(index);
    }

    private static long rate(
            long numerator,
            long denominator,
            long scale) {
        if (numerator < 0 || denominator <= 0 || scale <= 0) return -1;
        if (numerator > Long.MAX_VALUE / scale) return Long.MAX_VALUE;
        return numerator * scale / denominator;
    }

    private static long upperBound(
            long baseline,
            int relativeTolerancePermille,
            long absoluteTolerance) {
        long safeBaseline = Math.max(0, baseline);
        int relativePermille = Math.max(0,
                relativeTolerancePermille);
        long relative = relativePermille == 0
                ? 0
                : safeBaseline > (Long.MAX_VALUE - 999L)
                / relativePermille
                ? Long.MAX_VALUE
                : (safeBaseline * relativePermille + 999L) / 1_000L;
        long tolerance = Math.max(
                Math.max(0, absoluteTolerance), relative);
        return safeBaseline > Long.MAX_VALUE - tolerance
                ? Long.MAX_VALUE : safeBaseline + tolerance;
    }

    public enum Status {
        NO_DATA,
        INSUFFICIENT_SAMPLES,
        METRICS_MISSING,
        PASSED,
        FAILED
    }

    public enum Metric {
        FIRST_FRAME_MS(100, 250L, 150, 500L),
        REBUFFER_RATIO_PPM(100, 5_000L, 150, 10_000L),
        REBUFFER_COUNT(0, 1L, 0, 1L),
        PEAK_PSS_BYTES(100, 8L * 1024 * 1024,
                150, 16L * 1024 * 1024),
        DROPPED_FRAMES_PER_MINUTE_MILLI(
                100, 250L, 200, 1_000L),
        LIVE_LAG_MS(100, 1_000L, 200, 3_000L),
        ERROR_RATE_PPM(0, 50_000L, 0, 50_000L);

        private final int p50RelativeTolerancePermille;
        private final long p50AbsoluteTolerance;
        private final int p95RelativeTolerancePermille;
        private final long p95AbsoluteTolerance;

        Metric(
                int p50RelativeTolerancePermille,
                long p50AbsoluteTolerance,
                int p95RelativeTolerancePermille,
                long p95AbsoluteTolerance) {
            this.p50RelativeTolerancePermille =
                    p50RelativeTolerancePermille;
            this.p50AbsoluteTolerance = p50AbsoluteTolerance;
            this.p95RelativeTolerancePermille =
                    p95RelativeTolerancePermille;
            this.p95AbsoluteTolerance = p95AbsoluteTolerance;
        }
    }

    public record MetricComparison(
            boolean available,
            boolean passed,
            int automaticMetricSamples,
            int recommendedMetricSamples,
            long automaticP50,
            long automaticP95,
            long recommendedP50,
            long recommendedP95) {

        static MetricComparison missing(
                int automaticMetricSamples,
                int recommendedMetricSamples) {
            return new MetricComparison(
                    false,
                    false,
                    automaticMetricSamples,
                    recommendedMetricSamples,
                    -1,
                    -1,
                    -1,
                    -1);
        }
    }

    public record GroupReport(
            PlaybackProfileAbPolicy.GroupKey groupKey,
            Status status,
            int automaticSamples,
            int recommendedSamples,
            Map<Metric, MetricComparison> comparisons) {

        public GroupReport {
            status = status == null ? Status.NO_DATA : status;
            comparisons = comparisons == null
                    ? Map.of() : Map.copyOf(comparisons);
        }

        public List<Metric> failedMetrics() {
            List<Metric> failed = new ArrayList<>();
            for (Map.Entry<Metric, MetricComparison> entry
                    : comparisons.entrySet()) {
                if (entry.getValue().available()
                        && !entry.getValue().passed()) {
                    failed.add(entry.getKey());
                }
            }
            return List.copyOf(failed);
        }

        public List<Metric> missingMetrics() {
            List<Metric> missing = new ArrayList<>();
            for (Map.Entry<Metric, MetricComparison> entry
                    : comparisons.entrySet()) {
                if (!entry.getValue().available()) missing.add(entry.getKey());
            }
            return List.copyOf(missing);
        }

        public int comparisonSamples() {
            return recommendedSamples;
        }
    }

    public record Report(
            Status status,
            PlaybackProfileAbPolicy.Arm comparisonArm,
            int automaticSamples,
            int recommendedSamples,
            int totalGroups,
            int evaluableGroups,
            int passedGroups,
            int failedGroups,
            int insufficientGroups,
            List<GroupReport> groups) {

        public Report {
            status = status == null ? Status.NO_DATA : status;
            comparisonArm = comparisonArm == null
                    || comparisonArm == PlaybackProfileAbPolicy.Arm.AUTO
                    ? PlaybackProfileAbPolicy.Arm.RECOMMENDED
                    : comparisonArm;
            groups = groups == null ? List.of() : List.copyOf(groups);
        }

        public boolean mergeEligible() {
            return comparisonEligible();
        }

        public boolean comparisonEligible() {
            return status == Status.PASSED
                    && evaluableGroups > 0
                    && failedGroups == 0
                    && insufficientGroups == 0;
        }

        public int comparisonSamples() {
            return recommendedSamples;
        }
    }
}
