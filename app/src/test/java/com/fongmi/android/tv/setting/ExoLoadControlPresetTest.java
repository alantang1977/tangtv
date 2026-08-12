package com.fongmi.android.tv.setting;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExoLoadControlPresetTest {

    @Test
    public void fixedProfilesUseAuditedBufferCapacity() {
        assertPreset(PlaybackPerformanceSetting.PROFILE_RECOMMENDED, 10, 2, 0);
        assertPreset(PlaybackPerformanceSetting.PROFILE_AUTO, 10, 0, 0);
        assertPreset(PlaybackPerformanceSetting.PROFILE_COMPATIBLE, 1, 1, 0);
        assertPreset(PlaybackPerformanceSetting.PROFILE_LIGHTWEIGHT, 1, 1, 0);
    }

    @Test
    public void migrationPreservesCustomLoadControlValues() {
        assertTrue(PlaybackPerformanceSetting.shouldMigrateExoLoadControl(PlaybackPerformanceSetting.PROFILE_RECOMMENDED));
        assertTrue(PlaybackPerformanceSetting.shouldMigrateExoLoadControl(PlaybackPerformanceSetting.PROFILE_COMPATIBLE));
        assertTrue(PlaybackPerformanceSetting.shouldMigrateExoLoadControl(PlaybackPerformanceSetting.PROFILE_LIGHTWEIGHT));
        assertTrue(PlaybackPerformanceSetting.shouldMigrateExoLoadControl(PlaybackPerformanceSetting.PROFILE_AUTO));
        assertFalse(PlaybackPerformanceSetting.shouldMigrateExoLoadControl(PlaybackPerformanceSetting.PROFILE_CUSTOM));
        assertTrue(PlaybackPerformanceSetting.shouldMigrateExoBackBuffer(PlaybackPerformanceSetting.PROFILE_RECOMMENDED));
        assertTrue(PlaybackPerformanceSetting.shouldMigrateExoBackBuffer(PlaybackPerformanceSetting.PROFILE_AUTO));
        assertFalse(PlaybackPerformanceSetting.shouldMigrateExoBackBuffer(PlaybackPerformanceSetting.PROFILE_CUSTOM));
    }

    private static void assertPreset(int profile, int buffer, int bytesOption, int backBufferOption) {
        assertEquals(buffer, KernelPerformanceSetting.exoBufferForPreset(profile));
        assertEquals(bytesOption, KernelPerformanceSetting.exoBufferBytesOptionForPreset(profile));
        assertEquals(backBufferOption, KernelPerformanceSetting.exoBackBufferOptionForPreset(profile));
        assertFalse(ExoPerformanceSetting.prioritizeTimeForPreset(profile));
    }
}
