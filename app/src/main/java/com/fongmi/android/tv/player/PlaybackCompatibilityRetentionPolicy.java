package com.fongmi.android.tv.player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Fail-closed V-06B gate for any future Compatible-profile removal review. */
public final class PlaybackCompatibilityRetentionPolicy {

    private PlaybackCompatibilityRetentionPolicy() {
    }

    public static Assessment current() {
        return evaluate(currentEvidence());
    }

    public static Evidence currentEvidence() {
        EnumMap<Requirement, Coverage> coverage =
                new EnumMap<>(Requirement.class);

        // Existing recovery is deliberately classified as limited until it
        // covers the full failure family and has long-term device evidence.
        coverage.put(Requirement.EXO_CODEC_FAILURE, Coverage.LIMITED);
        coverage.put(Requirement.EXO_HARDWARE_MISREPORT, Coverage.LIMITED);
        coverage.put(Requirement.EXO_OUTPUT_MODE, Coverage.LIMITED);
        coverage.put(Requirement.EXO_PROTOCOL_RANGE, Coverage.LIMITED);

        coverage.put(Requirement.MPV_CODEC_FAILURE, Coverage.LIMITED);
        coverage.put(Requirement.MPV_HARDWARE_MISREPORT,
                Coverage.UNVERIFIED);
        coverage.put(Requirement.MPV_OUTPUT_MODE, Coverage.LIMITED);
        coverage.put(Requirement.MPV_PROTOCOL_RANGE, Coverage.LIMITED);

        coverage.put(Requirement.IJK_CODEC_FAILURE, Coverage.LIMITED);
        coverage.put(Requirement.IJK_HARDWARE_MISREPORT,
                Coverage.UNVERIFIED);
        coverage.put(Requirement.IJK_OUTPUT_MODE, Coverage.UNVERIFIED);
        coverage.put(Requirement.IJK_PROTOCOL_RANGE, Coverage.LIMITED);

        coverage.put(Requirement.VISUAL_CORRECTNESS,
                Coverage.UNOBSERVABLE);
        coverage.put(Requirement.LONG_TERM_DEVICE_EVIDENCE,
                Coverage.MISSING);
        return new Evidence(coverage);
    }

    public static Assessment evaluate(Evidence evidence) {
        Evidence safe = evidence == null
                ? Evidence.missing() : evidence;
        EnumMap<Coverage, Integer> counts =
                new EnumMap<>(Coverage.class);
        for (Coverage value : Coverage.values()) counts.put(value, 0);

        List<Finding> blockers = new ArrayList<>();
        for (Requirement requirement : Requirement.values()) {
            Coverage value = safe.coverage(requirement);
            counts.put(value, counts.get(value) + 1);
            if (value != Coverage.RELIABLE) {
                blockers.add(new Finding(requirement, value));
            }
        }
        Status status = blockers.isEmpty()
                ? Status.REVIEW_ELIGIBLE : Status.RETAIN_REQUIRED;
        return new Assessment(
                status,
                Requirement.values().length,
                counts.get(Coverage.RELIABLE),
                counts.get(Coverage.LIMITED),
                counts.get(Coverage.UNVERIFIED),
                counts.get(Coverage.UNOBSERVABLE),
                counts.get(Coverage.MISSING),
                List.copyOf(blockers));
    }

    public enum Status {
        RETAIN_REQUIRED,
        REVIEW_ELIGIBLE
    }

    public enum Coverage {
        RELIABLE,
        LIMITED,
        UNVERIFIED,
        UNOBSERVABLE,
        MISSING
    }

    public enum Area {
        CODEC_FAILURE,
        HARDWARE_MISREPORT,
        OUTPUT_MODE,
        PROTOCOL_RANGE,
        VISUAL_CORRECTNESS,
        LONG_TERM_DEVICE_EVIDENCE
    }

    public enum Requirement {
        EXO_CODEC_FAILURE(
                PlaybackAutoContext.Kernel.EXO,
                Area.CODEC_FAILURE),
        EXO_HARDWARE_MISREPORT(
                PlaybackAutoContext.Kernel.EXO,
                Area.HARDWARE_MISREPORT),
        EXO_OUTPUT_MODE(
                PlaybackAutoContext.Kernel.EXO,
                Area.OUTPUT_MODE),
        EXO_PROTOCOL_RANGE(
                PlaybackAutoContext.Kernel.EXO,
                Area.PROTOCOL_RANGE),
        MPV_CODEC_FAILURE(
                PlaybackAutoContext.Kernel.MPV,
                Area.CODEC_FAILURE),
        MPV_HARDWARE_MISREPORT(
                PlaybackAutoContext.Kernel.MPV,
                Area.HARDWARE_MISREPORT),
        MPV_OUTPUT_MODE(
                PlaybackAutoContext.Kernel.MPV,
                Area.OUTPUT_MODE),
        MPV_PROTOCOL_RANGE(
                PlaybackAutoContext.Kernel.MPV,
                Area.PROTOCOL_RANGE),
        IJK_CODEC_FAILURE(
                PlaybackAutoContext.Kernel.IJK,
                Area.CODEC_FAILURE),
        IJK_HARDWARE_MISREPORT(
                PlaybackAutoContext.Kernel.IJK,
                Area.HARDWARE_MISREPORT),
        IJK_OUTPUT_MODE(
                PlaybackAutoContext.Kernel.IJK,
                Area.OUTPUT_MODE),
        IJK_PROTOCOL_RANGE(
                PlaybackAutoContext.Kernel.IJK,
                Area.PROTOCOL_RANGE),
        VISUAL_CORRECTNESS(
                PlaybackAutoContext.Kernel.UNKNOWN,
                Area.VISUAL_CORRECTNESS),
        LONG_TERM_DEVICE_EVIDENCE(
                PlaybackAutoContext.Kernel.UNKNOWN,
                Area.LONG_TERM_DEVICE_EVIDENCE);

        private final PlaybackAutoContext.Kernel kernel;
        private final Area area;

        Requirement(
                PlaybackAutoContext.Kernel kernel,
                Area area) {
            this.kernel = kernel;
            this.area = area;
        }

        public PlaybackAutoContext.Kernel kernel() {
            return kernel;
        }

        public Area area() {
            return area;
        }

        public boolean kernelScoped() {
            return kernel != PlaybackAutoContext.Kernel.UNKNOWN;
        }
    }

    public record Evidence(Map<Requirement, Coverage> coverage) {

        public Evidence {
            EnumMap<Requirement, Coverage> safe =
                    new EnumMap<>(Requirement.class);
            if (coverage != null) safe.putAll(coverage);
            safe.entrySet().removeIf(entry -> entry.getValue() == null);
            coverage = Collections.unmodifiableMap(safe);
        }

        public static Evidence missing() {
            return new Evidence(Map.of());
        }

        public static Evidence allReliable() {
            EnumMap<Requirement, Coverage> coverage =
                    new EnumMap<>(Requirement.class);
            for (Requirement requirement : Requirement.values()) {
                coverage.put(requirement, Coverage.RELIABLE);
            }
            return new Evidence(coverage);
        }

        public Coverage coverage(Requirement requirement) {
            if (requirement == null) return Coverage.MISSING;
            return coverage.getOrDefault(requirement, Coverage.MISSING);
        }

        public Evidence with(
                Requirement requirement,
                Coverage value) {
            EnumMap<Requirement, Coverage> updated =
                    new EnumMap<>(Requirement.class);
            updated.putAll(coverage);
            if (requirement != null) {
                if (value == null) updated.remove(requirement);
                else updated.put(requirement, value);
            }
            return new Evidence(updated);
        }
    }

    public record Finding(
            Requirement requirement,
            Coverage coverage) {

        public Finding {
            coverage = coverage == null ? Coverage.MISSING : coverage;
        }
    }

    public record Assessment(
            Status status,
            int totalRequirements,
            int reliableRequirements,
            int limitedRequirements,
            int unverifiedRequirements,
            int unobservableRequirements,
            int missingRequirements,
            List<Finding> blockers) {

        public Assessment {
            totalRequirements = Math.max(0, totalRequirements);
            reliableRequirements = Math.max(0, reliableRequirements);
            limitedRequirements = Math.max(0, limitedRequirements);
            unverifiedRequirements = Math.max(0, unverifiedRequirements);
            unobservableRequirements = Math.max(0,
                    unobservableRequirements);
            missingRequirements = Math.max(0, missingRequirements);
            blockers = blockers == null
                    ? List.of() : List.copyOf(blockers);
            boolean complete = totalRequirements > 0
                    && reliableRequirements == totalRequirements
                    && limitedRequirements == 0
                    && unverifiedRequirements == 0
                    && unobservableRequirements == 0
                    && missingRequirements == 0
                    && blockers.isEmpty();
            status = status == Status.REVIEW_ELIGIBLE && complete
                    ? Status.REVIEW_ELIGIBLE : Status.RETAIN_REQUIRED;
        }

        public boolean retainRequired() {
            return status == Status.RETAIN_REQUIRED;
        }

        public boolean reviewEligible() {
            return status == Status.REVIEW_ELIGIBLE;
        }
    }
}
