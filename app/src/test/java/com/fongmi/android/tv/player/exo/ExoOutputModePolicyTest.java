package com.fongmi.android.tv.player.exo;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExoOutputModePolicyTest {

    @Test
    public void distinguishesTwentyThreeNineSevenSixFromTwentyFour() {
        ExoOutputModePolicy.Decision decision = select(1, ExoOutputModePolicy.Content.of(3840, 2160, 23.976f), ExoOutputModePolicy.Policy.frameRateOnly());

        assertEquals(2, decision.mode().id());
        assertTrue(decision.changeRequired());
    }

    @Test
    public void selectsIntegerRefreshMultipleForPalFilm() {
        ExoOutputModePolicy.Decision decision = select(1, ExoOutputModePolicy.Content.of(3840, 2160, 25f), ExoOutputModePolicy.Policy.frameRateOnly());

        assertEquals(4, decision.mode().id());
    }

    @Test
    public void selectsFiftyNineNineFourForTwentyNineNineSeven() {
        ExoOutputModePolicy.Decision decision = select(1, ExoOutputModePolicy.Content.of(3840, 2160, 29.97f), ExoOutputModePolicy.Policy.frameRateOnly());

        assertEquals(5, decision.mode().id());
    }

    @Test
    public void frameRateOnlyKeepsCurrentPhysicalResolution() {
        ExoOutputModePolicy.Decision decision = select(1, ExoOutputModePolicy.Content.of(1920, 1080, 23.976f), ExoOutputModePolicy.Policy.frameRateOnly());

        assertEquals(2, decision.mode().id());
        assertEquals(3840, decision.mode().width());
    }

    @Test
    public void resolutionAndRateCanSelectNativeContentMode() {
        ExoOutputModePolicy.Decision decision = select(1, ExoOutputModePolicy.Content.of(1920, 1080, 23.976f), ExoOutputModePolicy.Policy.resolutionAndRate());

        assertEquals(6, decision.mode().id());
        assertEquals(1920, decision.mode().width());
    }

    @Test
    public void fiveKContentFallsBackToLargestReliableOutputMode() {
        ExoOutputModePolicy.Decision decision = select(1, ExoOutputModePolicy.Content.of(5120, 2880, 23.976f), ExoOutputModePolicy.Policy.resolutionAndRate());

        assertEquals(2, decision.mode().id());
        assertEquals(3840, decision.mode().width());
    }

    @Test
    public void unknownFrameRateDoesNotRequestAChange() {
        ExoOutputModePolicy.Decision decision = select(1, ExoOutputModePolicy.Content.of(3840, 2160, 0), ExoOutputModePolicy.Policy.resolutionAndRate());

        assertEquals(1, decision.mode().id());
        assertFalse(decision.changeRequired());
    }

    private static ExoOutputModePolicy.Decision select(int currentModeId, ExoOutputModePolicy.Content content, ExoOutputModePolicy.Policy policy) {
        return ExoOutputModePolicy.select(List.of(
                ExoOutputModePolicy.Mode.of(1, 3840, 2160, 60f),
                ExoOutputModePolicy.Mode.of(2, 3840, 2160, 23.976f),
                ExoOutputModePolicy.Mode.of(3, 3840, 2160, 24f),
                ExoOutputModePolicy.Mode.of(4, 3840, 2160, 50f),
                ExoOutputModePolicy.Mode.of(5, 3840, 2160, 59.94f),
                ExoOutputModePolicy.Mode.of(6, 1920, 1080, 23.976f),
                ExoOutputModePolicy.Mode.of(7, 1920, 1080, 60f)
        ), currentModeId, content, policy);
    }
}
