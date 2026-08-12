package com.fongmi.android.tv.player.exo;

import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.scenario.PlaybackScenarioMatrix;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExoPlaybackScenarioMatrixTest {

    private static final ExoAutomaticVideoConstraintPolicy.Limit FOUR_K_BASELINE =
            new ExoAutomaticVideoConstraintPolicy.Limit(
                    3840, 2160, 60, 90_000_000);
    private static final List<ExoAutomaticVideoConstraintPolicy.Limit> VIDEO_TIERS =
            List.of(
                    FOUR_K_BASELINE,
                    new ExoAutomaticVideoConstraintPolicy.Limit(
                            2560, 1440, 60, 30_000_000),
                    new ExoAutomaticVideoConstraintPolicy.Limit(
                            1920, 1080, 30, 8_000_000),
                    new ExoAutomaticVideoConstraintPolicy.Limit(
                            1280, 720, 30, 4_000_000),
                    ExoAutomaticVideoConstraintPolicy.Limit.minimum());

    @Test
    public void representativeDevicesKeepOrderedTargetBufferTiers() {
        ExoTargetBufferPolicy.Decision lowRam = target(
                PlaybackScenarioMatrix.Id.LOW_RAM_HLS_VOD);
        ExoTargetBufferPolicy.Decision ordinary = target(
                PlaybackScenarioMatrix.Id.ORDINARY_TV_PROGRESSIVE);
        ExoTargetBufferPolicy.Decision fourK = target(
                PlaybackScenarioMatrix.Id.FOUR_K_HDR_DASH);

        assertEquals(mib(16), lowRam.targetBytes());
        assertEquals(mib(64), ordinary.targetBytes());
        assertEquals(mib(192), fourK.targetBytes());
        assertTrue(lowRam.targetBytes() < ordinary.targetBytes());
        assertTrue(ordinary.targetBytes() < fourK.targetBytes());
    }

    @Test
    public void everyScenarioRespectsMediaMemoryAndMedia3Guard() {
        for (PlaybackScenarioMatrix.Scenario scenario : PlaybackScenarioMatrix.all()) {
            ExoTargetBufferPolicy.Decision decision = target(scenario);
            String message = scenario.id().name();

            assertTrue(message, decision.targetBytes()
                    >= ExoTargetBufferPolicy.MIN_TARGET_BYTES);
            assertTrue(message, decision.targetBytes()
                    <= ExoTargetBufferPolicy.GUARD_TARGET_BYTES);
            assertTrue(message, decision.targetBytes() <= decision.mediaTierBytes());
            assertTrue(message, decision.targetBytes() <= decision.safeTierBytes());
        }
    }

    @Test
    public void softwareDecodeAndSevereThermalUseTheStrictIntersection() {
        PlaybackScenarioMatrix.Scenario scenario = PlaybackScenarioMatrix.get(
                PlaybackScenarioMatrix.Id.SOFTWARE_DECODE_THERMAL);
        ExoAutomaticVideoConstraintPolicy.Environment environment =
                ExoAutomaticVideoConstraintPolicy.environment(
                        scenario.context(PlaybackAutoContext.Kernel.EXO),
                        PlaybackScenarioMatrix.NOW_MS);
        ExoAutomaticVideoConstraintPolicy.Limit expected =
                ExoAutomaticVideoConstraintPolicy.SOFTWARE_DECODER_LIMIT.intersect(
                        ExoAutomaticVideoConstraintPolicy.THERMAL_SEVERE_LIMIT);

        assertEquals(expected, environment.cap());
        assertEquals(1920, environment.cap().width());
        assertEquals(1080, environment.cap().height());
        assertEquals(30, environment.cap().frameRate());
        assertEquals(6_000_000, environment.cap().maxVideoBitrate());
        assertTrue(environment.reasons().contains(
                ExoAutomaticVideoConstraintPolicy.Reason.SOFTWARE_DECODER));
        assertTrue(environment.reasons().contains(
                ExoAutomaticVideoConstraintPolicy.Reason.THERMAL_SEVERE));
    }

    @Test
    public void codecFalsePositiveOnlyTightensAfterExplicitFaultEvidence() {
        PlaybackScenarioMatrix.Scenario scenario = PlaybackScenarioMatrix.get(
                PlaybackScenarioMatrix.Id.CODEC_FALSE_POSITIVE_DASH);
        PlaybackAutoContext context = scenario.context(PlaybackAutoContext.Kernel.EXO);
        ExoAutomaticVideoConstraintPolicy.Environment environment =
                ExoAutomaticVideoConstraintPolicy.environment(
                        context, PlaybackScenarioMatrix.NOW_MS);
        ExoAutomaticVideoConstraintPolicy.SelectedTrack selected =
                new ExoAutomaticVideoConstraintPolicy.SelectedTrack(
                        scenario.width(), scenario.height(),
                        Math.round(scenario.frameRate()),
                        (int) scenario.averageBitrate());
        ExoAutomaticVideoConstraintPolicy.Input initialInput =
                new ExoAutomaticVideoConstraintPolicy.Input(
                        FOUR_K_BASELINE,
                        VIDEO_TIERS,
                        environment,
                        true,
                        selected,
                        ExoAutomaticVideoConstraintPolicy.ResourceMode.ADAPTIVE_VARIANTS,
                        PlaybackScenarioMatrix.NOW_MS);
        ExoAutomaticVideoConstraintPolicy.State initial =
                ExoAutomaticVideoConstraintPolicy.initial(FOUR_K_BASELINE);

        ExoAutomaticVideoConstraintPolicy.Decision withoutFault =
                ExoAutomaticVideoConstraintPolicy.evaluate(initial, initialInput);
        ExoAutomaticVideoConstraintPolicy.Decision firstFault =
                ExoAutomaticVideoConstraintPolicy.onFault(
                        withoutFault.state(), initialInput,
                        ExoAutomaticVideoConstraintPolicy.Fault.CODEC_ERROR);
        ExoAutomaticVideoConstraintPolicy.Decision repeatedFault =
                ExoAutomaticVideoConstraintPolicy.onFault(
                        firstFault.state(),
                        new ExoAutomaticVideoConstraintPolicy.Input(
                                FOUR_K_BASELINE,
                                VIDEO_TIERS,
                                environment,
                                true,
                                selected,
                                ExoAutomaticVideoConstraintPolicy.ResourceMode.ADAPTIVE_VARIANTS,
                                PlaybackScenarioMatrix.NOW_MS + 1_000),
                        ExoAutomaticVideoConstraintPolicy.Fault.CODEC_ERROR);

        assertEquals(FOUR_K_BASELINE, withoutFault.state().effective());
        assertEquals(VIDEO_TIERS.get(1), firstFault.state().effective());
        assertTrue(firstFault.faultStepped());
        assertFalse(repeatedFault.faultStepped());
        assertEquals(firstFault.state().effective(),
                repeatedFault.state().effective());
        assertEquals(ExoAutomaticVideoConstraintPolicy.Action.FAULT_COOLDOWN,
                repeatedFault.action());
    }

    private static ExoTargetBufferPolicy.Decision target(
            PlaybackScenarioMatrix.Id id) {
        return target(PlaybackScenarioMatrix.get(id));
    }

    private static ExoTargetBufferPolicy.Decision target(
            PlaybackScenarioMatrix.Scenario scenario) {
        PlaybackAutoContext context = scenario.context(PlaybackAutoContext.Kernel.EXO);
        PlaybackAutoContext.MemorySnapshot memory = scenario.memorySnapshot();
        long heapLimit = memory.javaHeapLimitBytes() == null
                ? 0 : memory.javaHeapLimitBytes();
        ExoBufferBudget.Budget fallback = ExoBufferBudget.calculate(
                ExoBufferBudget.MAX_TARGET_BYTES,
                heapLimit,
                Boolean.TRUE.equals(memory.lowRamDevice()));
        ExoTargetBufferPolicy.MediaDemand demand =
                new ExoTargetBufferPolicy.MediaDemand(
                        scenario.averageBitrate(),
                        ExoTargetBufferPolicy.DemandSource.SELECTED_TRACK,
                        PlaybackAutoContext.Confidence.HIGH,
                        scenario.peakBitrate(),
                        ExoTargetBufferPolicy.DemandSource.SELECTED_TRACK,
                        PlaybackAutoContext.Confidence.HIGH);
        return ExoTargetBufferPolicy.resolve(
                demand,
                0,
                fallback,
                context.device(),
                PlaybackScenarioMatrix.NOW_MS);
    }

    private static int mib(int value) {
        return value * 1024 * 1024;
    }
}
