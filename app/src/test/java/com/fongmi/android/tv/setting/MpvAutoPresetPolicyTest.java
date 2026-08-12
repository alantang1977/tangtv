package com.fongmi.android.tv.setting;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MpvAutoPresetPolicyTest {

    @Test
    public void automaticPresetNoLongerUsesRecommendedByteAndBackLimits() {
        assertEquals(0, KernelPerformanceSetting.mpvBufferBytesOptionForPreset(
                PlaybackPerformanceSetting.PROFILE_AUTO));
        assertEquals(0, KernelPerformanceSetting.mpvBackBufferOptionForPreset(
                PlaybackPerformanceSetting.PROFILE_AUTO));
        assertEquals(3, KernelPerformanceSetting.mpvBufferBytesOptionForPreset(
                PlaybackPerformanceSetting.PROFILE_RECOMMENDED));
        assertEquals(2, KernelPerformanceSetting.mpvBackBufferOptionForPreset(
                PlaybackPerformanceSetting.PROFILE_RECOMMENDED));
    }

    @Test
    public void migrationTouchesOnlyAutomaticMpvProfile() {
        assertTrue(PlaybackPerformanceSetting.shouldMigrateMpvAutoBaseline(
                PlaybackPerformanceSetting.PROFILE_AUTO));
        assertFalse(PlaybackPerformanceSetting.shouldMigrateMpvAutoBaseline(
                PlaybackPerformanceSetting.PROFILE_RECOMMENDED));
        assertFalse(PlaybackPerformanceSetting.shouldMigrateMpvAutoBaseline(
                PlaybackPerformanceSetting.PROFILE_COMPATIBLE));
        assertFalse(PlaybackPerformanceSetting.shouldMigrateMpvAutoBaseline(
                PlaybackPerformanceSetting.PROFILE_LIGHTWEIGHT));
        assertFalse(PlaybackPerformanceSetting.shouldMigrateMpvAutoBaseline(
                PlaybackPerformanceSetting.PROFILE_CUSTOM));
    }

    @Test
    public void automaticHlsTextNoLongerClaimsFixedHighestBitrate() {
        assertEquals("自动 · ≤15Mbps起步",
                MpvPerformanceSetting.getHlsBitrateText(true));
    }
}
