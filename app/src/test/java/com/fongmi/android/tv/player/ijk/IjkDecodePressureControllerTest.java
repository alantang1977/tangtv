package com.fongmi.android.tv.player.ijk;

import com.fongmi.android.tv.player.PlaybackAutoContext;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IjkDecodePressureControllerTest {

    private static final PlaybackAutoContext.SessionToken SESSION_A =
            new PlaybackAutoContext.SessionToken("p-decode-a", 1);
    private static final PlaybackAutoContext.SessionToken SESSION_B =
            new PlaybackAutoContext.SessionToken("p-decode-b", 2);
    private static final IjkDecodePressurePolicy.Config OFF =
            IjkDecodePressurePolicy.Config.automatic(
                    IjkDecodePressurePolicy.TuneMode.OFF);
    private static final IjkDecodePressurePolicy.Config MILD =
            IjkDecodePressurePolicy.Config.automatic(
                    IjkDecodePressurePolicy.TuneMode.MILD);
    private static final IjkDecodePressurePolicy.Config AGGRESSIVE =
            IjkDecodePressurePolicy.Config.automatic(
                    IjkDecodePressurePolicy.TuneMode.AGGRESSIVE);

    @Test
    public void initialAutomaticConfigStagesThreeFramesAndTuneOff() {
        IjkDecodePressureController controller = controller();

        IjkDecodePressureController.Decision decision =
                controller.stageInitial(SESSION_A, SESSION_A, true);

        assertEquals(IjkDecodePressureController.Action.STAGE,
                decision.action());
        assertEquals(3, decision.targetConfig().pictureQueue());
        assertEquals(IjkDecodePressurePolicy.TuneMode.OFF,
                decision.targetConfig().tuneMode());
    }

    @Test
    public void moderatePressureNeedsThreeSamplesAndTenSeconds() {
        IjkDecodePressureController controller = controller();

        assertEquals(IjkDecodePressureController.Reason.CONFIRMING_PRESSURE,
                controller.evaluate(moderate(0, OFF)).reason());
        assertEquals(IjkDecodePressureController.Reason.CONFIRMING_PRESSURE,
                controller.evaluate(moderate(5_000, OFF)).reason());
        IjkDecodePressureController.Decision decision =
                controller.evaluate(moderate(10_000, OFF));

        assertTrue(decision.requestsReload());
        assertEquals(MILD, decision.targetConfig());
        assertEquals(3, controller.snapshot().consecutiveRiskSamples());
    }

    @Test
    public void severePressureStillEscalatesOnlyOneLevelAtATime() {
        IjkDecodePressureController controller = controller();
        controller.evaluate(severe(0, OFF));
        controller.evaluate(severe(5_000, OFF));
        IjkDecodePressureController.Decision fromOff =
                controller.evaluate(severe(10_000, OFF));

        assertEquals(MILD, fromOff.targetConfig());
        assertTrue(controller.beginAction(SESSION_A, fromOff));
        controller.completeAction(SESSION_A, true);

        controller.evaluate(severe(20_000, MILD));
        controller.evaluate(severe(25_000, MILD));
        IjkDecodePressureController.Decision fromMild =
                controller.evaluate(severe(30_000, MILD));

        assertTrue(fromMild.requestsReload());
        assertEquals(AGGRESSIVE, fromMild.targetConfig());
    }

    @Test
    public void moderatePressureCannotEscalatePastMild() {
        IjkDecodePressureController controller = controller();

        IjkDecodePressureController.Decision decision =
                controller.evaluate(moderate(0, MILD));

        assertEquals(IjkDecodePressureController.Reason.ALREADY_AT_TARGET,
                decision.reason());
        assertFalse(decision.requestsReload());
    }

    @Test
    public void shortIntervalsAndLongGapsCannotFakeConfirmation() {
        IjkDecodePressureController controller = controller();
        controller.evaluate(moderate(0, OFF));

        assertEquals(IjkDecodePressureController.Reason.SAMPLE_TOO_SOON,
                controller.evaluate(moderate(1_000, OFF)).reason());
        controller.evaluate(moderate(5_000, OFF));
        IjkDecodePressureController.Decision afterGap =
                controller.evaluate(moderate(25_001, OFF));

        assertEquals(IjkDecodePressureController.Reason.CONFIRMING_PRESSURE,
                afterGap.reason());
        assertEquals(1, controller.snapshot().consecutiveRiskSamples());
        assertEquals(25_001, controller.snapshot().riskSinceMs());
    }

    @Test
    public void seekAndOtherReloadDiscardOldPressureEvidence() {
        IjkDecodePressureController controller = controller();
        controller.evaluate(moderate(0, OFF));
        controller.evaluate(moderate(5_000, OFF));
        controller.onUserSeek(SESSION_A, 6_000);

        assertEquals(IjkDecodePressureController.Reason.INELIGIBLE,
                controller.evaluate(moderate(10_000, OFF)).reason());
        assertEquals(0, controller.snapshot().consecutiveRiskSamples());
        assertEquals(IjkDecodePressureController.Reason.ACTION_PENDING,
                controller.evaluate(input(
                        22_000, OFF, false, true,
                        PlaybackAutoContext.DecodeMode.SOFTWARE,
                        PlaybackAutoContext.ThermalState.MODERATE,
                        30f, 25f, 24f)).reason());
    }

    @Test
    public void failedActionRestoresAppliedConfigAndReleasesState() {
        IjkDecodePressureController controller = controller();
        IjkDecodePressureController.Decision decision = confirmedModerate(
                controller, OFF, 0);

        assertTrue(controller.beginAction(SESSION_A, decision));
        assertEquals(MILD, controller.snapshot().stagedConfig());
        controller.completeAction(SESSION_A, false);

        assertEquals(OFF, controller.snapshot().stagedConfig());
        assertEquals(1, controller.snapshot().actionAttempts());
        assertEquals(1, controller.snapshot().failedActions());
        assertFalse(controller.snapshot().actionPending());
    }

    @Test
    public void healthyNominalThermalStagesRecoveryWithoutReload() {
        IjkDecodePressureController controller = controller();
        stageApplied(controller, AGGRESSIVE);

        controller.evaluate(healthy(0, AGGRESSIVE));
        controller.evaluate(healthy(10_000, AGGRESSIVE));
        controller.evaluate(healthy(20_000, AGGRESSIVE));
        IjkDecodePressureController.Decision first =
                controller.evaluate(healthy(30_000, AGGRESSIVE));

        assertEquals(IjkDecodePressureController.Action.STAGE, first.action());
        assertEquals(MILD, first.targetConfig());
        assertFalse(first.requestsReload());

        controller.evaluate(healthy(40_000, AGGRESSIVE));
        controller.evaluate(healthy(50_000, AGGRESSIVE));
        controller.evaluate(healthy(60_000, AGGRESSIVE));
        IjkDecodePressureController.Decision second =
                controller.evaluate(healthy(70_000, AGGRESSIVE));

        assertEquals(IjkDecodePressureController.Action.STAGE, second.action());
        assertEquals(OFF, second.targetConfig());
    }

    @Test
    public void thermalPressureDoesNotCountAsHealthyRecovery() {
        IjkDecodePressureController controller = controller();
        stageApplied(controller, MILD);

        IjkDecodePressureController.Decision decision =
                controller.evaluate(input(
                        0, MILD, false, false,
                        PlaybackAutoContext.DecodeMode.SOFTWARE,
                        PlaybackAutoContext.ThermalState.NOMINAL,
                        30f, 18f, 16f));

        assertEquals(IjkDecodePressureController.Reason.HEALTHY,
                decision.reason());
        assertEquals(0, controller.snapshot().consecutiveRecoverySamples());
    }

    @Test
    public void hardwareDecoderStagesTuneOffWithoutReload() {
        IjkDecodePressureController controller = controller();
        stageApplied(controller, MILD);

        IjkDecodePressureController.Decision decision =
                controller.evaluate(input(
                        0, MILD, false, false,
                        PlaybackAutoContext.DecodeMode.HARDWARE,
                        PlaybackAutoContext.ThermalState.SEVERE,
                        30f, 15f, 12f));

        assertEquals(IjkDecodePressureController.Action.STAGE,
                decision.action());
        assertEquals(IjkDecodePressureController.Reason.HARDWARE_STAGED_OFF,
                decision.reason());
        assertEquals(OFF, decision.targetConfig());
    }

    @Test
    public void staleSessionAndOutOfOrderSamplesAreIgnored() {
        IjkDecodePressureController controller = controller();
        IjkDecodePressureController.Input stale = new IjkDecodePressureController.Input(
                SESSION_B, SESSION_B, policy(
                PlaybackAutoContext.DecodeMode.SOFTWARE,
                PlaybackAutoContext.ThermalState.MODERATE,
                30f, 25f, 24f), OFF, false, 0);

        assertEquals(IjkDecodePressureController.Reason.STALE_SESSION,
                controller.evaluate(stale).reason());
        controller.evaluate(moderate(10_000, OFF));
        assertEquals(IjkDecodePressureController.Reason.STALE_SAMPLE,
                controller.evaluate(moderate(5_000, OFF)).reason());
    }

    @Test
    public void newSessionClearsAttemptsAndStagedTune() {
        IjkDecodePressureController controller = controller();
        IjkDecodePressureController.Decision decision = confirmedModerate(
                controller, OFF, 0);
        controller.beginAction(SESSION_A, decision);
        controller.completeAction(SESSION_A, true);

        controller.beginSession(SESSION_B);

        assertEquals(0, controller.snapshot().actionAttempts());
        assertEquals(OFF, controller.snapshot().stagedConfig());
        assertEquals(SESSION_B, controller.snapshot().session());
    }

    private static IjkDecodePressureController controller() {
        IjkDecodePressureController controller =
                new IjkDecodePressureController();
        controller.beginSession(SESSION_A);
        controller.stageInitial(SESSION_A, SESSION_A, true);
        return controller;
    }

    private static IjkDecodePressureController.Decision confirmedModerate(
            IjkDecodePressureController controller,
            IjkDecodePressurePolicy.Config applied,
            long start) {
        controller.evaluate(moderate(start, applied));
        controller.evaluate(moderate(start + 5_000, applied));
        return controller.evaluate(moderate(start + 10_000, applied));
    }

    private static void stageApplied(
            IjkDecodePressureController controller,
            IjkDecodePressurePolicy.Config target) {
        IjkDecodePressureController.Decision decision =
                new IjkDecodePressureController.Decision(
                        IjkDecodePressureController.Action.RELOAD,
                        OFF,
                        target,
                        IjkDecodePressurePolicy.assess(policy(
                                PlaybackAutoContext.DecodeMode.SOFTWARE,
                                PlaybackAutoContext.ThermalState.SEVERE,
                                30f, 18f, 16f)),
                        IjkDecodePressureController.Reason.PRESSURE_RELOAD);
        assertTrue(controller.beginAction(SESSION_A, decision));
        controller.completeAction(SESSION_A, true);
    }

    private static IjkDecodePressureController.Input moderate(
            long now,
            IjkDecodePressurePolicy.Config applied) {
        return input(now, applied, false, false,
                PlaybackAutoContext.DecodeMode.SOFTWARE,
                PlaybackAutoContext.ThermalState.MODERATE,
                30f, 25f, 24f);
    }

    private static IjkDecodePressureController.Input severe(
            long now,
            IjkDecodePressurePolicy.Config applied) {
        return input(now, applied, false, false,
                PlaybackAutoContext.DecodeMode.SOFTWARE,
                PlaybackAutoContext.ThermalState.SEVERE,
                30f, 18f, 16f);
    }

    private static IjkDecodePressureController.Input healthy(
            long now,
            IjkDecodePressurePolicy.Config applied) {
        return input(now, applied, false, false,
                PlaybackAutoContext.DecodeMode.SOFTWARE,
                PlaybackAutoContext.ThermalState.NOMINAL,
                30f, 30f, 30f);
    }

    private static IjkDecodePressureController.Input input(
            long now,
            IjkDecodePressurePolicy.Config applied,
            boolean userSeeking,
            boolean reloadPending,
            PlaybackAutoContext.DecodeMode decode,
            PlaybackAutoContext.ThermalState thermal,
            float target,
            float decodeFps,
            float outputFps) {
        IjkDecodePressurePolicy.Input policy = policy(
                decode, thermal, target, decodeFps, outputFps);
        if (userSeeking) policy = policy.withRuntimeGuards(true, false);
        return new IjkDecodePressureController.Input(
                SESSION_A, SESSION_A, policy, applied,
                reloadPending, now);
    }

    private static IjkDecodePressurePolicy.Input policy(
            PlaybackAutoContext.DecodeMode decode,
            PlaybackAutoContext.ThermalState thermal,
            float target,
            float decodeFps,
            float outputFps) {
        return new IjkDecodePressurePolicy.Input(
                true, true, true, true, true, false, false,
                true, decode,
                true, thermal,
                target > 0, target,
                new IjkDecodePressurePolicy.DecodeSnapshot(
                        true, decodeFps, outputFps));
    }
}
