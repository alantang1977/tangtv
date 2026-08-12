package com.fongmi.android.tv.player.ijk;

import com.fongmi.android.tv.player.PlaybackAutoContext;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IjkDecodePressurePolicyTest {

    @Test
    public void automaticPrepareAlwaysUsesThreeFrames() {
        IjkDecodePressurePolicy.Config config =
                IjkDecodePressurePolicy.prepareConfig(
                        true,
                        new IjkDecodePressurePolicy.Config(
                                8,
                                IjkDecodePressurePolicy.TuneMode.MILD),
                        false,
                        8,
                        IjkDecodePressurePolicy.TuneMode.AGGRESSIVE);

        assertEquals(3, config.pictureQueue());
        assertEquals(IjkDecodePressurePolicy.TuneMode.MILD,
                config.tuneMode());
    }

    @Test
    public void manualPrepareKeepsThreeFiveEightAndRequiresSoftRequest() {
        assertEquals(3, manual(2, true).pictureQueue());
        assertEquals(5, manual(4, true).pictureQueue());
        assertEquals(8, manual(7, true).pictureQueue());
        assertEquals(IjkDecodePressurePolicy.TuneMode.AGGRESSIVE,
                manual(8, true).tuneMode());
        assertEquals(IjkDecodePressurePolicy.TuneMode.OFF,
                manual(8, false).tuneMode());
    }

    @Test
    public void hardwareDecoderNeverRequestsSoftTune() {
        IjkDecodePressurePolicy.Assessment assessment = assess(
                PlaybackAutoContext.DecodeMode.HARDWARE,
                PlaybackAutoContext.ThermalState.SEVERE,
                30f, 12f, 10f);

        assertEquals(IjkDecodePressurePolicy.Reason.HARDWARE_DECODER,
                assessment.reason());
        assertFalse(assessment.actionableRisk());
        assertEquals(IjkDecodePressurePolicy.TuneMode.OFF,
                assessment.suggestedTune());
    }

    @Test
    public void decoderAndThermalUnknownSuppressQualityLoss() {
        IjkDecodePressurePolicy.Assessment decoderUnknown =
                IjkDecodePressurePolicy.assess(input(
                        false,
                        PlaybackAutoContext.DecodeMode.UNKNOWN,
                        false,
                        PlaybackAutoContext.ThermalState.UNKNOWN,
                        30f, 12f, 10f));
        IjkDecodePressurePolicy.Assessment thermalUnknown =
                IjkDecodePressurePolicy.assess(input(
                        true,
                        PlaybackAutoContext.DecodeMode.SOFTWARE,
                        false,
                        PlaybackAutoContext.ThermalState.UNKNOWN,
                        30f, 12f, 10f));

        assertEquals(IjkDecodePressurePolicy.Reason.DECODER_UNKNOWN,
                decoderUnknown.reason());
        assertEquals(IjkDecodePressurePolicy.Reason.THERMAL_UNKNOWN,
                thermalUnknown.reason());
        assertFalse(decoderUnknown.actionableRisk());
        assertFalse(thermalUnknown.actionableRisk());
    }

    @Test
    public void nominalThermalHoldsEvenWhenFpsIsLow() {
        IjkDecodePressurePolicy.Assessment assessment = assess(
                PlaybackAutoContext.DecodeMode.SOFTWARE,
                PlaybackAutoContext.ThermalState.NOMINAL,
                30f, 18f, 16f);

        assertEquals(IjkDecodePressurePolicy.Pressure.SEVERE,
                assessment.pressure());
        assertEquals(IjkDecodePressurePolicy.Reason.THERMAL_NOMINAL_HOLD,
                assessment.reason());
        assertFalse(assessment.actionableRisk());
        assertFalse(assessment.recoveryEligible());
    }

    @Test
    public void moderateThermalCapsSevereFpsPressureAtMild() {
        IjkDecodePressurePolicy.Assessment assessment = assess(
                PlaybackAutoContext.DecodeMode.SOFTWARE,
                PlaybackAutoContext.ThermalState.MODERATE,
                30f, 18f, 16f);

        assertEquals(IjkDecodePressurePolicy.Pressure.MODERATE,
                assessment.pressure());
        assertTrue(assessment.actionableRisk());
        assertEquals(IjkDecodePressurePolicy.TuneMode.MILD,
                assessment.suggestedTune());
    }

    @Test
    public void severeThermalAllowsAggressiveSuggestion() {
        IjkDecodePressurePolicy.Assessment assessment = assess(
                PlaybackAutoContext.DecodeMode.SOFTWARE,
                PlaybackAutoContext.ThermalState.SEVERE,
                30f, 18f, 16f);

        assertEquals(IjkDecodePressurePolicy.Pressure.SEVERE,
                assessment.pressure());
        assertTrue(assessment.actionableRisk());
        assertEquals(IjkDecodePressurePolicy.TuneMode.AGGRESSIVE,
                assessment.suggestedTune());
    }

    @Test
    public void severeThermalEscalatesSustainedModerateFpsPressure() {
        IjkDecodePressurePolicy.Assessment assessment = assess(
                PlaybackAutoContext.DecodeMode.SOFTWARE,
                PlaybackAutoContext.ThermalState.SEVERE,
                30f, 25f, 24f);

        assertEquals(IjkDecodePressurePolicy.Pressure.SEVERE,
                assessment.pressure());
        assertEquals(IjkDecodePressurePolicy.TuneMode.AGGRESSIVE,
                assessment.suggestedTune());
    }

    @Test
    public void nominalHealthySoftwareDecodeCanRecoverQuality() {
        IjkDecodePressurePolicy.Assessment assessment = assess(
                PlaybackAutoContext.DecodeMode.SOFTWARE,
                PlaybackAutoContext.ThermalState.NOMINAL,
                30f, 30f, 30f);

        assertEquals(IjkDecodePressurePolicy.Reason.HEALTHY,
                assessment.reason());
        assertTrue(assessment.recoveryEligible());
        assertFalse(assessment.actionableRisk());
    }

    @Test
    public void fpsGapCanShowPressureWhenTargetRateIsUnknown() {
        IjkDecodePressurePolicy.Assessment assessment =
                IjkDecodePressurePolicy.assess(input(
                        true,
                        PlaybackAutoContext.DecodeMode.SOFTWARE,
                        true,
                        PlaybackAutoContext.ThermalState.SEVERE,
                        -1f, 30f, 18f));

        assertEquals(IjkDecodePressurePolicy.Pressure.SEVERE,
                assessment.pressure());
        assertTrue(assessment.actionableRisk());
        assertEquals(-1, assessment.metrics().decodeRatioPermille());
    }

    @Test
    public void missingNativeFpsStaysUnknown() {
        IjkDecodePressurePolicy.Assessment assessment =
                IjkDecodePressurePolicy.assess(new IjkDecodePressurePolicy.Input(
                        true, true, true, true, true, false, false,
                        true, PlaybackAutoContext.DecodeMode.SOFTWARE,
                        true, PlaybackAutoContext.ThermalState.SEVERE,
                        true, 30f,
                        IjkDecodePressurePolicy.DecodeSnapshot.unknown()));

        assertEquals(IjkDecodePressurePolicy.Reason.FPS_UNKNOWN,
                assessment.reason());
        assertFalse(assessment.actionableRisk());
    }

    @Test
    public void inactiveSeekAndSpeedGuardsRunBeforeMetrics() {
        IjkDecodePressurePolicy.Input base = input(
                true,
                PlaybackAutoContext.DecodeMode.SOFTWARE,
                true,
                PlaybackAutoContext.ThermalState.SEVERE,
                30f, 18f, 16f);

        assertEquals(IjkDecodePressurePolicy.Reason.INACTIVE,
                IjkDecodePressurePolicy.assess(new IjkDecodePressurePolicy.Input(
                        true, true, false, true, true, false, false,
                        base.decoderUsable(), base.actualDecode(),
                        base.thermalUsable(), base.thermal(),
                        base.targetFrameRateUsable(), base.targetFrameRate(),
                        base.snapshot())).reason());
        assertEquals(IjkDecodePressurePolicy.Reason.NON_UNIT_SPEED,
                IjkDecodePressurePolicy.assess(new IjkDecodePressurePolicy.Input(
                        true, true, true, true, false, false, false,
                        base.decoderUsable(), base.actualDecode(),
                        base.thermalUsable(), base.thermal(),
                        base.targetFrameRateUsable(), base.targetFrameRate(),
                        base.snapshot())).reason());
        assertEquals(IjkDecodePressurePolicy.Reason.USER_SEEK,
                IjkDecodePressurePolicy.assess(new IjkDecodePressurePolicy.Input(
                        true, true, true, true, true, true, false,
                        base.decoderUsable(), base.actualDecode(),
                        base.thermalUsable(), base.thermal(),
                        base.targetFrameRateUsable(), base.targetFrameRate(),
                        base.snapshot())).reason());
    }

    private static IjkDecodePressurePolicy.Config manual(
            int pictureQueue,
            boolean requestedSoft) {
        return IjkDecodePressurePolicy.prepareConfig(
                false,
                IjkDecodePressurePolicy.automaticInitialConfig(),
                requestedSoft,
                pictureQueue,
                IjkDecodePressurePolicy.TuneMode.AGGRESSIVE);
    }

    private static IjkDecodePressurePolicy.Assessment assess(
            PlaybackAutoContext.DecodeMode decode,
            PlaybackAutoContext.ThermalState thermal,
            float targetFps,
            float decodeFps,
            float outputFps) {
        return IjkDecodePressurePolicy.assess(input(
                true, decode, true, thermal,
                targetFps, decodeFps, outputFps));
    }

    private static IjkDecodePressurePolicy.Input input(
            boolean decoderUsable,
            PlaybackAutoContext.DecodeMode decode,
            boolean thermalUsable,
            PlaybackAutoContext.ThermalState thermal,
            float targetFps,
            float decodeFps,
            float outputFps) {
        return new IjkDecodePressurePolicy.Input(
                true, true, true, true, true, false, false,
                decoderUsable, decode,
                thermalUsable, thermal,
                targetFps > 0, targetFps,
                new IjkDecodePressurePolicy.DecodeSnapshot(
                        true, decodeFps, outputFps));
    }
}
