package com.fongmi.android.tv.player.exo;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExoAdaptiveVideoBitratePolicyTest {

    @Test
    public void downgradeThresholdPreservesExistingTwentyFivePercentHeadroom() {
        assertTrue(ExoAdaptiveVideoBitratePolicy.shouldDowngrade(20_000_000, 24_999_999));
        assertFalse(ExoAdaptiveVideoBitratePolicy.shouldDowngrade(20_000_000, 25_000_000));
        assertFalse(ExoAdaptiveVideoBitratePolicy.shouldDowngrade(20_000_000, 30_000_000));
        assertFalse(ExoAdaptiveVideoBitratePolicy.shouldDowngrade(20_000_000, 0));
    }

    @Test
    public void bandwidthCapUsesEightyPercentOfEstimate() {
        assertEquals(8_000_000, ExoAdaptiveVideoBitratePolicy.resolveTrackBitrateCap(12_000_000, 10_000_000));
        assertEquals(12_000_000, ExoAdaptiveVideoBitratePolicy.resolveTrackBitrateCap(12_000_000, 100_000_000));
    }

    @Test
    public void capUsesOneMegabitFloorWithoutRaisingExistingLowerLimit() {
        assertEquals(1_000_000, ExoAdaptiveVideoBitratePolicy.resolveTrackBitrateCap(4_000_000, 500_000));
        assertEquals(500_000, ExoAdaptiveVideoBitratePolicy.resolveTrackBitrateCap(500_000, 500_000));
    }

    @Test
    public void invalidAndExtremeEstimatesDoNotOverflowOrInventCaps() {
        assertEquals(12_000_000, ExoAdaptiveVideoBitratePolicy.resolveTrackBitrateCap(12_000_000, 0));
        assertEquals(0, ExoAdaptiveVideoBitratePolicy.resolveTrackBitrateCap(0, 10_000_000));
        assertEquals(Integer.MAX_VALUE, ExoAdaptiveVideoBitratePolicy.resolveTrackBitrateCap(Integer.MAX_VALUE, Long.MAX_VALUE));
        assertFalse(ExoAdaptiveVideoBitratePolicy.shouldDowngrade(Integer.MAX_VALUE, Long.MAX_VALUE));
    }

    @Test
    public void profileNominalBitrateIsPreservedWhileTrackCapChanges() {
        ExoUtil.EnhancedVideoProfile profile = new ExoUtil.EnhancedVideoProfile(2560, 1440, 12_000_000, 60, 12_000_000);

        ExoUtil.EnhancedVideoProfile capped = profile.withBandwidthCap(10_000_000);

        assertEquals(12_000_000, capped.bitrate());
        assertEquals(8_000_000, capped.maxVideoBitrate());
        assertEquals(2560, capped.width());
        assertEquals(1440, capped.height());
    }

    @Test
    public void selectedTrackCheckDistinguishesUnknownWithinAndExceededCaps() {
        assertEquals(ExoAdaptiveVideoBitratePolicy.Status.UNKNOWN, ExoAdaptiveVideoBitratePolicy.checkSelectedTrack(8_000_000, -1).status());
        assertEquals(ExoAdaptiveVideoBitratePolicy.Status.WITHIN_CAP, ExoAdaptiveVideoBitratePolicy.checkSelectedTrack(8_000_000, 8_000_000).status());
        assertEquals(ExoAdaptiveVideoBitratePolicy.Status.EXCEEDS_CAP, ExoAdaptiveVideoBitratePolicy.checkSelectedTrack(8_000_000, 9_000_000).status());
    }
}
