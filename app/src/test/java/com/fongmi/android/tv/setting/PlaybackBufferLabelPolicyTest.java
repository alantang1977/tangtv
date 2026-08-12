package com.fongmi.android.tv.setting;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PlaybackBufferLabelPolicyTest {

    @Test
    public void forwardBufferLabelsExposeActualTimeInsteadOfLevels() {
        assertEquals("自动 · 网络30～60秒", PlaybackPerformanceSetting.forwardBufferText(
                PlayerSetting.EXO, PlaybackPerformanceSetting.PROFILE_AUTO, 10));
        assertEquals("15～30秒", PlaybackPerformanceSetting.forwardBufferText(
                PlayerSetting.EXO, PlaybackPerformanceSetting.PROFILE_CUSTOM, 1));
        assertEquals("30～60秒", PlaybackPerformanceSetting.forwardBufferText(
                PlayerSetting.EXO, PlaybackPerformanceSetting.PROFILE_CUSTOM, 10));
        assertEquals("自动 · 目标30秒", PlaybackPerformanceSetting.forwardBufferText(
                PlayerSetting.MPV, PlaybackPerformanceSetting.PROFILE_AUTO, 10));
        assertEquals("目标15秒", PlaybackPerformanceSetting.forwardBufferText(
                PlayerSetting.MPV, PlaybackPerformanceSetting.PROFILE_CUSTOM, 1));
        assertEquals("由读包内存和水位控制", PlaybackPerformanceSetting.forwardBufferText(
                PlayerSetting.IJK, PlaybackPerformanceSetting.PROFILE_AUTO, 10));
    }

    @Test
    public void memoryLabelsSeparateAutomaticAndFixedLimits() {
        assertEquals("自动 · 16～192MB", PlaybackPerformanceSetting.memoryBufferText(
                PlayerSetting.EXO, PlaybackPerformanceSetting.PROFILE_AUTO, 0));
        assertEquals("自动 · 24～192MB", PlaybackPerformanceSetting.memoryBufferText(
                PlayerSetting.MPV, PlaybackPerformanceSetting.PROFILE_AUTO, 0));
        assertEquals("128MB", PlaybackPerformanceSetting.memoryBufferText(
                PlayerSetting.EXO, PlaybackPerformanceSetting.PROFILE_CUSTOM, 2));
        assertEquals("自动 · 读包4～15MB", PlaybackPerformanceSetting.ijkMemoryBufferText(
                PlaybackPerformanceSetting.PROFILE_AUTO, 15));
        assertEquals("读包8MB", PlaybackPerformanceSetting.ijkMemoryBufferText(
                PlaybackPerformanceSetting.PROFILE_CUSTOM, 8));
    }

    @Test
    public void retentionAndDiskCacheLabelsUseTheirRealUnits() {
        assertEquals("30秒", PlaybackPerformanceSetting.playedDataRetentionText(
                PlayerSetting.EXO, PlaybackPerformanceSetting.PROFILE_CUSTOM, 2));
        assertEquals("中等 · 至少32MB", PlaybackPerformanceSetting.playedDataRetentionText(
                PlayerSetting.MPV, PlaybackPerformanceSetting.PROFILE_CUSTOM, 2));
        assertEquals("无独立保留", PlaybackPerformanceSetting.playedDataRetentionText(
                PlayerSetting.IJK, PlaybackPerformanceSetting.PROFILE_AUTO, 0));
        assertEquals("1GB", PlaybackPerformanceSetting.playbackDiskCacheText(3));
    }
}
