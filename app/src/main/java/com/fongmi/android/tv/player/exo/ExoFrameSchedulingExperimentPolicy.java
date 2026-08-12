package com.fongmi.android.tv.player.exo;

import java.util.HashSet;
import java.util.Set;

/** Pure admission and assignment policy for the EXO frame scheduling A/B experiment. */
public final class ExoFrameSchedulingExperimentPolicy {

    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final long BASELINE_EARLY_SCHEDULING_THRESHOLD_US = 50_000L;

    private ExoFrameSchedulingExperimentPolicy() {
    }

    public static AssignmentResolution resolveAssignment(
            RawAssignment rawAssignment,
            String currentDeviceDigest) {
        RawAssignment raw = rawAssignment == null
                ? RawAssignment.missing() : rawAssignment;
        if (raw.schemaVersion() == null
                && raw.deviceDigest() == null
                && raw.unitId() == null) {
            return AssignmentResolution.unassigned();
        }
        Integer version = integer(raw.schemaVersion());
        if (version == null || version < 0) {
            return AssignmentResolution.invalid(Status.CORRUPT);
        }
        if (version > CURRENT_SCHEMA_VERSION) {
            return AssignmentResolution.invalid(Status.FUTURE_SCHEMA);
        }
        if (version != CURRENT_SCHEMA_VERSION
                || !(raw.deviceDigest() instanceof String deviceDigest)
                || !(raw.unitId() instanceof String unitId)
                || !ExoFrameSchedulingExperimentIdentity.validDigest(deviceDigest)) {
            return AssignmentResolution.invalid(Status.CORRUPT);
        }
        Unit unit = Unit.fromId(unitId);
        if (unit == null) {
            return AssignmentResolution.invalid(Status.CORRUPT);
        }
        if (!ExoFrameSchedulingExperimentIdentity.validDigest(currentDeviceDigest)
                || !deviceDigest.equals(currentDeviceDigest)) {
            return new AssignmentResolution(
                    Assignment.none(), Status.STALE_DEVICE, false);
        }
        return new AssignmentResolution(
                new Assignment(deviceDigest, unit), Status.CURRENT, true);
    }

    public static Decision decide(Input input) {
        Input safe = input == null ? Input.stable() : input;
        AssignmentResolution assignment = safe.assignment();
        if (!safe.automatic()) {
            return stable(safe, Reason.NOT_AUTOMATIC);
        }
        if (!safe.hardDecode()) {
            return stable(safe, Reason.NOT_HARDWARE_DECODE);
        }
        if (!safe.experimentAllowed()) {
            return stable(safe, Reason.EXPERIMENT_DISABLED);
        }
        if (!safe.dynamicSchedulingEnabled()) {
            return stable(safe, Reason.DYNAMIC_SCHEDULING_DISABLED);
        }
        if (safe.synchronousCodecQueue()) {
            return stable(safe, Reason.SYNCHRONOUS_CODEC_QUEUE);
        }
        if (assignment.status() == Status.UNASSIGNED) {
            return stable(safe, Reason.DEVICE_NOT_ENROLLED);
        }
        if (!assignment.sourceValid() || !assignment.assignment().active()) {
            return stable(safe, Reason.INVALID_ASSIGNMENT);
        }
        Unit unit = assignment.assignment().unit();
        return new Decision(
                new RendererSettings(
                        unit.earlySchedulingThresholdUs(),
                        unit.durationToProgressEnabled()),
                unit,
                true,
                Reason.EXPERIMENT_APPLIED,
                assignment.status(),
                assignment.assignment().deviceDigest(),
                safe.dynamicSchedulingEnabled(),
                safe.synchronousCodecQueue());
    }

    public static Decision stableDecision(
            boolean stableDurationToProgressEnabled,
            boolean dynamicSchedulingEnabled,
            boolean synchronousCodecQueue) {
        return stable(
                new Input(
                        false,
                        false,
                        false,
                        dynamicSchedulingEnabled,
                        synchronousCodecQueue,
                        stableDurationToProgressEnabled,
                        AssignmentResolution.unassigned()),
                Reason.NOT_AUTOMATIC);
    }

    public static boolean registryIsValid() {
        Set<String> ids = new HashSet<>();
        for (Unit unit : Unit.values()) {
            if (unit.id().isBlank()
                    || unit.earlySchedulingThresholdUs()
                    < BASELINE_EARLY_SCHEDULING_THRESHOLD_US
                    || unit.earlySchedulingThresholdUs() > 100_000L
                    || !ids.add(unit.id())) {
                return false;
            }
        }
        return ids.size() == 6;
    }

    private static Decision stable(Input input, Reason reason) {
        boolean durationToProgress = input.stableDurationToProgressEnabled()
                && !input.synchronousCodecQueue();
        AssignmentResolution assignment = input.assignment();
        return new Decision(
                new RendererSettings(
                        BASELINE_EARLY_SCHEDULING_THRESHOLD_US,
                        durationToProgress),
                null,
                false,
                reason,
                assignment.status(),
                assignment.assignment().deviceDigest(),
                input.dynamicSchedulingEnabled(),
                input.synchronousCodecQueue());
    }

    private static Integer integer(Object value) {
        if (!(value instanceof Byte
                || value instanceof Short
                || value instanceof Integer
                || value instanceof Long)) return null;
        long result = ((Number) value).longValue();
        return result < Integer.MIN_VALUE || result > Integer.MAX_VALUE
                ? null : (int) result;
    }

    public enum Unit {
        EARLY_50_DURATION_OFF("early-50-duration-off", 50_000L, false),
        EARLY_50_DURATION_ON("early-50-duration-on", 50_000L, true),
        EARLY_75_DURATION_OFF("early-75-duration-off", 75_000L, false),
        EARLY_75_DURATION_ON("early-75-duration-on", 75_000L, true),
        EARLY_100_DURATION_OFF("early-100-duration-off", 100_000L, false),
        EARLY_100_DURATION_ON("early-100-duration-on", 100_000L, true);

        private final String id;
        private final long earlySchedulingThresholdUs;
        private final boolean durationToProgressEnabled;

        Unit(
                String id,
                long earlySchedulingThresholdUs,
                boolean durationToProgressEnabled) {
            this.id = id;
            this.earlySchedulingThresholdUs = earlySchedulingThresholdUs;
            this.durationToProgressEnabled = durationToProgressEnabled;
        }

        public String id() {
            return id;
        }

        public long earlySchedulingThresholdUs() {
            return earlySchedulingThresholdUs;
        }

        public boolean durationToProgressEnabled() {
            return durationToProgressEnabled;
        }

        public static Unit fromId(String value) {
            if (value == null) return null;
            for (Unit unit : values()) {
                if (unit.id.equals(value)) return unit;
            }
            return null;
        }
    }

    public enum Status {
        UNASSIGNED,
        CURRENT,
        CORRUPT,
        FUTURE_SCHEMA,
        STALE_DEVICE
    }

    public enum Reason {
        EXPERIMENT_APPLIED("experiment-applied"),
        NOT_AUTOMATIC("not-automatic"),
        NOT_HARDWARE_DECODE("not-hardware-decode"),
        EXPERIMENT_DISABLED("experiment-disabled"),
        DYNAMIC_SCHEDULING_DISABLED("dynamic-scheduling-disabled"),
        SYNCHRONOUS_CODEC_QUEUE("synchronous-codec-queue"),
        DEVICE_NOT_ENROLLED("device-not-enrolled"),
        INVALID_ASSIGNMENT("invalid-assignment");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record RawAssignment(
            Object schemaVersion,
            Object deviceDigest,
            Object unitId) {

        public static RawAssignment missing() {
            return new RawAssignment(null, null, null);
        }
    }

    public record Assignment(String deviceDigest, Unit unit) {

        public Assignment {
            deviceDigest = deviceDigest == null ? "" : deviceDigest;
        }

        public static Assignment none() {
            return new Assignment("", null);
        }

        public boolean active() {
            return ExoFrameSchedulingExperimentIdentity.validDigest(deviceDigest)
                    && unit != null;
        }
    }

    public record AssignmentResolution(
            Assignment assignment,
            Status status,
            boolean sourceValid) {

        public AssignmentResolution {
            assignment = assignment == null ? Assignment.none() : assignment;
            status = status == null ? Status.CORRUPT : status;
        }

        public static AssignmentResolution unassigned() {
            return new AssignmentResolution(
                    Assignment.none(), Status.UNASSIGNED, true);
        }

        private static AssignmentResolution invalid(Status status) {
            return new AssignmentResolution(Assignment.none(), status, false);
        }

        public boolean failClosed() {
            return !sourceValid || status == Status.FUTURE_SCHEMA;
        }
    }

    public record Input(
            boolean automatic,
            boolean hardDecode,
            boolean experimentAllowed,
            boolean dynamicSchedulingEnabled,
            boolean synchronousCodecQueue,
            boolean stableDurationToProgressEnabled,
            AssignmentResolution assignment) {

        public Input {
            assignment = assignment == null
                    ? AssignmentResolution.unassigned() : assignment;
        }

        public static Input stable() {
            return new Input(false, false, false,
                    false, false, false,
                    AssignmentResolution.unassigned());
        }
    }

    public record RendererSettings(
            long earlySchedulingThresholdUs,
            boolean durationToProgressEnabled) {

        public RendererSettings {
            if (earlySchedulingThresholdUs != 75_000L
                    && earlySchedulingThresholdUs != 100_000L) {
                earlySchedulingThresholdUs =
                        BASELINE_EARLY_SCHEDULING_THRESHOLD_US;
            }
        }
    }

    public record Decision(
            RendererSettings rendererSettings,
            Unit unit,
            boolean experimentApplied,
            Reason reason,
            Status assignmentStatus,
            String deviceDigest,
            boolean dynamicSchedulingEnabled,
            boolean synchronousCodecQueue) {

        public Decision {
            rendererSettings = rendererSettings == null
                    ? new RendererSettings(
                    BASELINE_EARLY_SCHEDULING_THRESHOLD_US, false)
                    : rendererSettings;
            if (!experimentApplied) unit = null;
            reason = reason == null ? Reason.INVALID_ASSIGNMENT : reason;
            assignmentStatus = assignmentStatus == null
                    ? Status.CORRUPT : assignmentStatus;
            deviceDigest = ExoFrameSchedulingExperimentIdentity.validDigest(deviceDigest)
                    ? deviceDigest : "";
        }

        public String sessionUnitId() {
            if (experimentApplied && unit != null) return unit.id();
            return "stable-50-duration-"
                    + (rendererSettings.durationToProgressEnabled() ? "on" : "off");
        }

        public boolean sameRendererSettings(Decision other) {
            return other != null
                    && rendererSettings.equals(other.rendererSettings);
        }
    }
}
