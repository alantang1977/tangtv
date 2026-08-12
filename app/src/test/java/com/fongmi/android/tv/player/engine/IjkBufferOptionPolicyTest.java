package com.fongmi.android.tv.player.engine;

import com.fongmi.android.tv.player.ijk.IjkBufferPolicy;
import com.fongmi.android.tv.setting.IjkPerformanceSetting;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IjkBufferOptionPolicyTest {

    @Test
    public void automaticPrepareUsesStagedConfig() {
        IjkBufferPolicy.Config staged = new IjkBufferPolicy.Config(
                4, 100, 300, 1_000);

        IjkBufferOptionPolicy.Decision decision =
                IjkBufferOptionPolicy.resolve(
                        true, staged, "https://example.invalid/live.m3u8",
                        IjkPerformanceSetting.SCENE_AUTO,
                        15, 0, 500, 2_000, 5_000);

        assertEquals(staged, decision.config());
        assertEquals(4L * IjkBufferPolicy.MIB, decision.maxBufferBytes());
        assertFalse(decision.infiniteBuffer());
    }

    @Test
    public void fixedPrepareIgnoresAutomaticStageAndKeepsUserValues() {
        IjkBufferOptionPolicy.Decision decision =
                IjkBufferOptionPolicy.resolve(
                        false, IjkBufferPolicy.safeInitialConfig(),
                        "https://example.invalid/movie.mp4",
                        IjkPerformanceSetting.SCENE_VOD,
                        15, 256L * IjkBufferPolicy.MIB,
                        500, 2_000, 5_000);

        assertEquals(new IjkBufferPolicy.Config(
                15, 500, 2_000, 5_000), decision.config());
        assertEquals(256L * IjkBufferPolicy.MIB, decision.maxBufferBytes());
        assertFalse(decision.infiniteBuffer());
    }

    @Test
    public void realtimePrepareRemainsFinite() {
        IjkBufferOptionPolicy.Decision decision =
                IjkBufferOptionPolicy.resolve(
                        true, null, "rtsp://example.invalid/live",
                        IjkPerformanceSetting.SCENE_AUTO,
                        15, 256L * IjkBufferPolicy.MIB,
                        500, 2_000, 5_000);

        assertTrue(decision.realtime());
        assertEquals(IjkBufferPolicy.safeInitialConfig(), decision.config());
        assertFalse(decision.infiniteBuffer());
    }
}
