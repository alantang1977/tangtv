package com.fongmi.android.tv.player.exo;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ExoFrameSchedulingExperimentPolicyTest {

    private static final String DEVICE =
            ExoFrameSchedulingExperimentIdentity.deviceDigest(
                    "fingerprint", "vendor", "model", 10, "1.0", "media3");

    @Test
    public void stablePathKeepsMedia3ThresholdAndExistingDurationSetting() {
        ExoFrameSchedulingExperimentPolicy.Decision decision =
                ExoFrameSchedulingExperimentPolicy.decide(
                        new ExoFrameSchedulingExperimentPolicy.Input(
                                true,
                                true,
                                false,
                                true,
                                false,
                                true,
                                current(ExoFrameSchedulingExperimentPolicy.Unit
                                        .EARLY_100_DURATION_OFF)));

        assertFalse(decision.experimentApplied());
        assertEquals(50_000L,
                decision.rendererSettings().earlySchedulingThresholdUs());
        assertTrue(decision.rendererSettings().durationToProgressEnabled());
        assertEquals(
                ExoFrameSchedulingExperimentPolicy.Reason.EXPERIMENT_DISABLED,
                decision.reason());
    }

    @Test
    public void everyRegisteredUnitProducesOnlyTheApprovedFactorCombination() {
        Set<String> ids = new HashSet<>();
        for (ExoFrameSchedulingExperimentPolicy.Unit unit
                : ExoFrameSchedulingExperimentPolicy.Unit.values()) {
            ExoFrameSchedulingExperimentPolicy.Decision decision = decide(unit);

            assertTrue(decision.experimentApplied());
            assertEquals(unit.id(), decision.sessionUnitId());
            assertEquals(unit.earlySchedulingThresholdUs(),
                    decision.rendererSettings().earlySchedulingThresholdUs());
            assertEquals(unit.durationToProgressEnabled(),
                    decision.rendererSettings().durationToProgressEnabled());
            assertTrue(ids.add(unit.id()));
        }
        assertEquals(6, ids.size());
        assertTrue(ExoFrameSchedulingExperimentPolicy.registryIsValid());
    }

    @Test
    public void experimentRequiresAutoHardDecodeDynamicSchedulingAndGate() {
        ExoFrameSchedulingExperimentPolicy.AssignmentResolution assignment =
                current(ExoFrameSchedulingExperimentPolicy.Unit
                        .EARLY_75_DURATION_ON);

        assertEquals(
                ExoFrameSchedulingExperimentPolicy.Reason.NOT_AUTOMATIC,
                decide(false, true, true, true, false, assignment).reason());
        assertEquals(
                ExoFrameSchedulingExperimentPolicy.Reason.NOT_HARDWARE_DECODE,
                decide(true, false, true, true, false, assignment).reason());
        assertEquals(
                ExoFrameSchedulingExperimentPolicy.Reason.EXPERIMENT_DISABLED,
                decide(true, true, false, true, false, assignment).reason());
        assertEquals(
                ExoFrameSchedulingExperimentPolicy.Reason
                        .DYNAMIC_SCHEDULING_DISABLED,
                decide(true, true, true, false, false, assignment).reason());
        assertEquals(
                ExoFrameSchedulingExperimentPolicy.Reason
                        .SYNCHRONOUS_CODEC_QUEUE,
                decide(true, true, true, true, true, assignment).reason());
    }

    @Test
    public void missingAssignmentDoesNotEnrollDeviceImplicitly() {
        ExoFrameSchedulingExperimentPolicy.AssignmentResolution resolution =
                ExoFrameSchedulingExperimentPolicy.resolveAssignment(
                        null, DEVICE);
        ExoFrameSchedulingExperimentPolicy.Decision decision =
                decide(true, true, true, true, false, resolution);

        assertEquals(ExoFrameSchedulingExperimentPolicy.Status.UNASSIGNED,
                resolution.status());
        assertTrue(resolution.sourceValid());
        assertFalse(decision.experimentApplied());
        assertEquals(
                ExoFrameSchedulingExperimentPolicy.Reason.DEVICE_NOT_ENROLLED,
                decision.reason());
    }

    @Test
    public void corruptAndUnknownUnitAssignmentsFailClosed() {
        ExoFrameSchedulingExperimentPolicy.AssignmentResolution corrupt =
                ExoFrameSchedulingExperimentPolicy.resolveAssignment(
                        new ExoFrameSchedulingExperimentPolicy.RawAssignment(
                                1, DEVICE, "early-250-duration-on"),
                        DEVICE);
        ExoFrameSchedulingExperimentPolicy.AssignmentResolution wrongType =
                ExoFrameSchedulingExperimentPolicy.resolveAssignment(
                        new ExoFrameSchedulingExperimentPolicy.RawAssignment(
                                1.0d, DEVICE,
                                ExoFrameSchedulingExperimentPolicy.Unit
                                        .EARLY_50_DURATION_ON.id()),
                        DEVICE);

        assertEquals(ExoFrameSchedulingExperimentPolicy.Status.CORRUPT,
                corrupt.status());
        assertTrue(corrupt.failClosed());
        assertEquals(ExoFrameSchedulingExperimentPolicy.Status.CORRUPT,
                wrongType.status());
        assertFalse(decide(true, true, true, true, false, corrupt)
                .experimentApplied());
    }

    @Test
    public void futureSchemaAndDifferentDeviceCannotApply() {
        ExoFrameSchedulingExperimentPolicy.AssignmentResolution future =
                ExoFrameSchedulingExperimentPolicy.resolveAssignment(
                        new ExoFrameSchedulingExperimentPolicy.RawAssignment(
                                99,
                                DEVICE,
                                ExoFrameSchedulingExperimentPolicy.Unit
                                        .EARLY_75_DURATION_OFF.id()),
                        DEVICE);
        ExoFrameSchedulingExperimentPolicy.AssignmentResolution stale =
                ExoFrameSchedulingExperimentPolicy.resolveAssignment(
                        new ExoFrameSchedulingExperimentPolicy.RawAssignment(
                                1,
                                DEVICE,
                                ExoFrameSchedulingExperimentPolicy.Unit
                                        .EARLY_75_DURATION_OFF.id()),
                        ExoFrameSchedulingExperimentIdentity.deviceDigest(
                                "other", "vendor", "model", 10,
                                "1.0", "media3"));

        assertEquals(ExoFrameSchedulingExperimentPolicy.Status.FUTURE_SCHEMA,
                future.status());
        assertTrue(future.failClosed());
        assertEquals(ExoFrameSchedulingExperimentPolicy.Status.STALE_DEVICE,
                stale.status());
        assertFalse(stale.sourceValid());
        assertNull(stale.assignment().unit());
    }

    @Test
    public void invalidRendererThresholdNormalizesToBaseline() {
        ExoFrameSchedulingExperimentPolicy.RendererSettings settings =
                new ExoFrameSchedulingExperimentPolicy.RendererSettings(
                        (1L << 32) + 75_000L, true);

        assertEquals(50_000L, settings.earlySchedulingThresholdUs());
    }

    private static ExoFrameSchedulingExperimentPolicy.Decision decide(
            ExoFrameSchedulingExperimentPolicy.Unit unit) {
        return decide(true, true, true, true, false, current(unit));
    }

    private static ExoFrameSchedulingExperimentPolicy.Decision decide(
            boolean automatic,
            boolean hardDecode,
            boolean allowed,
            boolean dynamic,
            boolean synchronous,
            ExoFrameSchedulingExperimentPolicy.AssignmentResolution
                    assignment) {
        return ExoFrameSchedulingExperimentPolicy.decide(
                new ExoFrameSchedulingExperimentPolicy.Input(
                        automatic,
                        hardDecode,
                        allowed,
                        dynamic,
                        synchronous,
                        true,
                        assignment));
    }

    private static ExoFrameSchedulingExperimentPolicy.AssignmentResolution
    current(ExoFrameSchedulingExperimentPolicy.Unit unit) {
        return ExoFrameSchedulingExperimentPolicy.resolveAssignment(
                new ExoFrameSchedulingExperimentPolicy.RawAssignment(
                        1, DEVICE, unit.id()),
                DEVICE);
    }
}
