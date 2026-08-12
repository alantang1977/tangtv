package com.fongmi.android.tv.player.ijk;

import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.scenario.PlaybackScenarioMatrix;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IjkPlaybackScenarioMatrixTest {

    @Test
    public void everyScenarioUsesAFiniteQueueTierAndOrderedWatermarks() {
        for (PlaybackScenarioMatrix.Scenario scenario : PlaybackScenarioMatrix.all()) {
            IjkBufferPolicy.Decision decision = buffer(scenario);
            IjkBufferPolicy.Config config = decision.target();
            String message = scenario.id().name();

            assertTrue(message, decision.managed());
            assertTrue(message,
                    config.bufferMb() == IjkBufferPolicy.LOW_BUFFER_MB
                            || config.bufferMb() == IjkBufferPolicy.BALANCED_BUFFER_MB
                            || config.bufferMb() == IjkBufferPolicy.HIGH_BUFFER_MB);
            assertTrue(message, config.firstWaterMs() <= config.nextWaterMs());
            assertTrue(message, config.nextWaterMs() <= config.lastWaterMs());
            assertTrue(message, config.maxBufferBytes()
                    <= (long) IjkBufferPolicy.HIGH_BUFFER_MB * IjkBufferPolicy.MIB);
        }
    }

    @Test
    public void lowRamRealtimeAndHighLiveLagUseTheSmallQueue() {
        assertBuffer(PlaybackScenarioMatrix.Id.LOW_RAM_HLS_VOD,
                IjkBufferPolicy.LOW_BUFFER_MB);
        assertBuffer(PlaybackScenarioMatrix.Id.RTSP_LIVE,
                IjkBufferPolicy.LOW_BUFFER_MB);
        assertBuffer(PlaybackScenarioMatrix.Id.HLS_LIVE,
                IjkBufferPolicy.LOW_BUFFER_MB);
        assertBuffer(PlaybackScenarioMatrix.Id.LL_HLS_LIVE,
                IjkBufferPolicy.LOW_BUFFER_MB);
    }

    @Test
    public void highBitrateFourKVodStopsAtTheFiniteHighTier() {
        IjkBufferPolicy.Decision decision = buffer(
                PlaybackScenarioMatrix.get(PlaybackScenarioMatrix.Id.FOUR_K_HDR_DASH));

        assertEquals(IjkBufferPolicy.HIGH_BUFFER_MB,
                decision.target().bufferMb());
        assertEquals(IjkBufferPolicy.HIGH_BUFFER_MB,
                decision.memoryCeilingMb());
        assertTrue(decision.mediaDemandBytes()
                > (long) IjkBufferPolicy.BALANCED_BUFFER_MB * IjkBufferPolicy.MIB);
    }

    @Test
    public void automaticPictureQueueRemainsThreeForEveryScenario() {
        for (PlaybackScenarioMatrix.Scenario scenario : PlaybackScenarioMatrix.all()) {
            IjkDecodePressurePolicy.TuneMode stagedTune =
                    scenario.decodeMode() == PlaybackAutoContext.DecodeMode.SOFTWARE
                            ? IjkDecodePressurePolicy.TuneMode.AGGRESSIVE
                            : IjkDecodePressurePolicy.TuneMode.OFF;
            IjkDecodePressurePolicy.Config config =
                    IjkDecodePressurePolicy.prepareConfig(
                            true,
                            IjkDecodePressurePolicy.Config.automatic(stagedTune),
                            scenario.decodeMode()
                                    == PlaybackAutoContext.DecodeMode.SOFTWARE,
                            16,
                            IjkDecodePressurePolicy.TuneMode.AGGRESSIVE);

            assertEquals(scenario.id().name(),
                    IjkDecodePressurePolicy.AUTOMATIC_PICTURE_QUEUE,
                    config.pictureQueue());
            assertEquals(scenario.id().name(), stagedTune, config.tuneMode());
        }
    }

    @Test
    public void severeSoftwareDecodeSuggestsAggressiveButSeekSuppressesIt() {
        PlaybackScenarioMatrix.Scenario scenario = PlaybackScenarioMatrix.get(
                PlaybackScenarioMatrix.Id.SOFTWARE_DECODE_THERMAL);
        IjkDecodePressurePolicy.Input input = decodeInput(scenario, false);

        IjkDecodePressurePolicy.Assessment pressure =
                IjkDecodePressurePolicy.assess(input);
        IjkDecodePressurePolicy.Assessment seeking =
                IjkDecodePressurePolicy.assess(input.withRuntimeGuards(true, false));

        assertEquals(IjkDecodePressurePolicy.Pressure.SEVERE,
                pressure.pressure());
        assertTrue(pressure.actionableRisk());
        assertEquals(IjkDecodePressurePolicy.TuneMode.AGGRESSIVE,
                pressure.suggestedTune());
        assertEquals(IjkDecodePressurePolicy.Reason.USER_SEEK,
                seeking.reason());
        assertFalse(seeking.actionableRisk());
        assertEquals(IjkDecodePressurePolicy.TuneMode.OFF,
                seeking.suggestedTune());
    }

    @Test
    public void rtspUsesRealtimeWatermarksWithoutInventingLiveEdge() {
        IjkBufferPolicy.Decision decision = buffer(
                PlaybackScenarioMatrix.get(PlaybackScenarioMatrix.Id.RTSP_LIVE));

        assertEquals(IjkBufferPolicy.LOW_BUFFER_MB,
                decision.target().bufferMb());
        assertEquals(100, decision.target().firstWaterMs());
        assertEquals(300, decision.target().nextWaterMs());
        assertEquals(1_000, decision.target().lastWaterMs());
        assertEquals(-1, decision.targetOffsetMs());
        assertFalse(decision.liveLagHigh());
        assertEquals(IjkBufferPolicy.Reason.REALTIME_BASELINE,
                decision.reason());
    }

    private static void assertBuffer(
            PlaybackScenarioMatrix.Id id,
            int expectedMb) {
        IjkBufferPolicy.Decision decision = buffer(PlaybackScenarioMatrix.get(id));
        assertEquals(id.name(), expectedMb, decision.target().bufferMb());
    }

    private static IjkBufferPolicy.Decision buffer(
            PlaybackScenarioMatrix.Scenario scenario) {
        return IjkBufferPolicy.resolve(new IjkBufferPolicy.Request(
                true,
                true,
                true,
                scenario.protocol(),
                true,
                scenario.streamKind(),
                true,
                scenario.manifest(),
                true,
                scenario.memoryPressure(),
                true,
                scenario.memorySnapshot(),
                true,
                scenario.averageBitrate(),
                true,
                scenario.rebufferCount(),
                scenario.liveLagMs() >= 0,
                scenario.liveLagMs()));
    }

    private static IjkDecodePressurePolicy.Input decodeInput(
            PlaybackScenarioMatrix.Scenario scenario,
            boolean userSeeking) {
        return new IjkDecodePressurePolicy.Input(
                true,
                true,
                true,
                true,
                true,
                userSeeking,
                false,
                true,
                scenario.decodeMode(),
                true,
                scenario.thermal(),
                true,
                scenario.frameRate(),
                new IjkDecodePressurePolicy.DecodeSnapshot(
                        true,
                        scenario.decodeFps(),
                        scenario.outputFps()));
    }
}
