package com.fongmi.android.tv.player;

import com.fongmi.android.tv.setting.PlaybackPerformanceSetting;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Conservative V-06A admission and coverage gate for Auto versus Light. */
public final class PlaybackLightweightAssessmentPolicy {

    private PlaybackLightweightAssessmentPolicy() {
    }

    public static PlaybackProfileAbPolicy.Arm armForProfile(int profile) {
        if (profile == PlaybackPerformanceSetting.PROFILE_AUTO) {
            return PlaybackProfileAbPolicy.Arm.AUTO;
        }
        if (profile == PlaybackPerformanceSetting.PROFILE_LIGHTWEIGHT) {
            return PlaybackProfileAbPolicy.Arm.LIGHTWEIGHT;
        }
        return null;
    }

    public static PlaybackProfileAbPolicy.GroupResolution resolveGroup(
            PlaybackAutoContext context,
            String deviceDigest,
            long elapsedRealtimeMs) {
        PlaybackProfileAbPolicy.GroupResolution base =
                PlaybackProfileAbPolicy.resolveGroup(
                        context, deviceDigest, elapsedRealtimeMs);
        if (!base.ready()) return base;
        if (base.key().decodeMode()
                == PlaybackAutoContext.DecodeMode.SOFTWARE) {
            return base;
        }
        PlaybackAutoContext.Fact<PlaybackAutoContext.MemorySnapshot> memory =
                context.device().memorySnapshot();
        if (memory == null || !memory.isUsable(elapsedRealtimeMs)
                || memory.value() == null
                || memory.value().lowRamDevice() == null) {
            return PlaybackProfileAbPolicy.GroupResolution.invalid(
                    PlaybackProfileAbPolicy.GroupReason
                            .MEMORY_CLASS_UNKNOWN);
        }
        return Boolean.TRUE.equals(memory.value().lowRamDevice())
                ? base
                : PlaybackProfileAbPolicy.GroupResolution.invalid(
                PlaybackProfileAbPolicy.GroupReason
                        .NOT_LIGHTWEIGHT_SCENARIO);
    }

    public static Report evaluate(PlaybackProfileAbStore.Snapshot snapshot) {
        PlaybackProfileAbAcceptancePolicy.Report comparison =
                PlaybackProfileAbAcceptancePolicy.evaluate(
                        snapshot,
                        PlaybackProfileAbPolicy.Arm.LIGHTWEIGHT);
        EnumSet<CoverageCell> covered =
                EnumSet.noneOf(CoverageCell.class);
        for (PlaybackProfileAbAcceptancePolicy.GroupReport group
                : comparison.groups()) {
            if (group.status()
                    != PlaybackProfileAbAcceptancePolicy.Status.PASSED
                    || group.groupKey() == null) continue;
            CoverageCell cell = CoverageCell.from(group.groupKey());
            if (cell != null) covered.add(cell);
        }
        Status status = switch (comparison.status()) {
            case NO_DATA -> Status.NO_DATA;
            case INSUFFICIENT_SAMPLES -> Status.INSUFFICIENT_SAMPLES;
            case METRICS_MISSING -> Status.METRICS_MISSING;
            case FAILED -> Status.FAILED;
            case PASSED -> covered.size() == CoverageCell.values().length
                    ? Status.PASSED : Status.COVERAGE_MISSING;
        };
        return new Report(status, comparison, Set.copyOf(covered));
    }

    public enum Status {
        NO_DATA,
        INSUFFICIENT_SAMPLES,
        METRICS_MISSING,
        COVERAGE_MISSING,
        PASSED,
        FAILED
    }

    public enum CoverageKind {
        LOW_RAM_HARDWARE,
        SOFTWARE
    }

    public enum CoverageCell {
        EXO_LOW_RAM_HARDWARE(
                PlaybackAutoContext.Kernel.EXO,
                CoverageKind.LOW_RAM_HARDWARE),
        EXO_SOFTWARE(
                PlaybackAutoContext.Kernel.EXO,
                CoverageKind.SOFTWARE),
        MPV_LOW_RAM_HARDWARE(
                PlaybackAutoContext.Kernel.MPV,
                CoverageKind.LOW_RAM_HARDWARE),
        MPV_SOFTWARE(
                PlaybackAutoContext.Kernel.MPV,
                CoverageKind.SOFTWARE),
        IJK_LOW_RAM_HARDWARE(
                PlaybackAutoContext.Kernel.IJK,
                CoverageKind.LOW_RAM_HARDWARE),
        IJK_SOFTWARE(
                PlaybackAutoContext.Kernel.IJK,
                CoverageKind.SOFTWARE);

        private final PlaybackAutoContext.Kernel kernel;
        private final CoverageKind kind;

        CoverageCell(
                PlaybackAutoContext.Kernel kernel,
                CoverageKind kind) {
            this.kernel = kernel;
            this.kind = kind;
        }

        public PlaybackAutoContext.Kernel kernel() {
            return kernel;
        }

        public CoverageKind kind() {
            return kind;
        }

        private static CoverageCell from(
                PlaybackProfileAbPolicy.GroupKey key) {
            if (key == null) return null;
            CoverageKind kind = key.decodeMode()
                    == PlaybackAutoContext.DecodeMode.SOFTWARE
                    ? CoverageKind.SOFTWARE
                    : key.decodeMode()
                    == PlaybackAutoContext.DecodeMode.HARDWARE
                    ? CoverageKind.LOW_RAM_HARDWARE : null;
            if (kind == null) return null;
            for (CoverageCell cell : values()) {
                if (cell.kernel == key.kernel() && cell.kind == kind) {
                    return cell;
                }
            }
            return null;
        }
    }

    public record Report(
            Status status,
            PlaybackProfileAbAcceptancePolicy.Report comparison,
            Set<CoverageCell> covered) {

        public Report {
            status = status == null ? Status.NO_DATA : status;
            comparison = comparison == null
                    ? PlaybackProfileAbAcceptancePolicy.evaluate(
                    new PlaybackProfileAbStore.Snapshot(List.of()),
                    PlaybackProfileAbPolicy.Arm.LIGHTWEIGHT)
                    : comparison;
            covered = covered == null
                    ? Set.of() : Set.copyOf(covered);
        }

        public boolean reviewEligible() {
            return status == Status.PASSED
                    && comparison.comparisonEligible()
                    && covered.size() == CoverageCell.values().length;
        }

        public List<CoverageCell> missingCoverage() {
            List<CoverageCell> missing = new ArrayList<>();
            for (CoverageCell cell : CoverageCell.values()) {
                if (!covered.contains(cell)) missing.add(cell);
            }
            return List.copyOf(missing);
        }
    }
}
