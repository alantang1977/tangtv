package com.fongmi.android.tv.player;

import com.fongmi.android.tv.player.engine.PlayerEngine;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class DolbyVisionFormatLabelTest {

    @Test
    public void nativeDv7KeepsTheOriginalCodecText() {
        PlayerEngine.VideoPlaybackDetails details = details(false);

        assertEquals("Dolby Vision DV.07",
                DolbyVisionFormatLabel.formatName(details));
        assertEquals("dvhe.07.06",
                DolbyVisionFormatLabel.codecText(details));
    }

    @Test
    public void fallbackDv7MarksHdr10BesideTheCodec() {
        PlayerEngine.VideoPlaybackDetails details = details(true);

        assertEquals("Dolby Vision DV.07",
                DolbyVisionFormatLabel.formatName(details));
        assertEquals("dvhe.07.06（回退HDR10）",
                DolbyVisionFormatLabel.codecText(details));
    }

    private static PlayerEngine.VideoPlaybackDetails details(
            boolean fallback) {
        return new PlayerEngine.VideoPlaybackDetails(
                "dvhe.07.06", 7, 6, "video/hevc",
                "c2.qti.hevc.decoder", "", null, fallback);
    }
}
