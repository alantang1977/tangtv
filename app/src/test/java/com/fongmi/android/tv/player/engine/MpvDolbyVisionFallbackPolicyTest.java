package com.fongmi.android.tv.player.engine;

import androidx.media3.mpvplayer.MpvPlayer;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MpvDolbyVisionFallbackPolicyTest {

    @Test
    public void gpuOutputMeansDv7BaseLayerFallback() {
        MpvPlayer.VideoTrackDiagnostics details = details(7);

        assertTrue(MpvPlayerEngine.isDolbyVisionHdr10Fallback(
                details, "gpu-next"));
        assertTrue(MpvPlayerEngine.isDolbyVisionHdr10Fallback(
                details, "gpu"));
    }

    @Test
    public void configuredFallbackAlsoCoversSurfaceDirect() {
        assertTrue(MpvPlayerEngine.isConfiguredDv7Hdr10Fallback(
                details(7), true, true));
        assertFalse(MpvPlayerEngine.isDolbyVisionHdr10Fallback(
                details(7), "mediacodec_embed"));
        assertFalse(MpvPlayerEngine.isDolbyVisionHdr10Fallback(
                details(5), "gpu-next"));
    }

    @Test
    public void configuredFallbackMarksDv7WithoutWaitingForVo() {
        assertTrue(MpvPlayerEngine.isConfiguredDv7Hdr10Fallback(
                details(7), true, true));
        assertFalse(MpvPlayerEngine.isConfiguredDv7Hdr10Fallback(
                details(7), false, true));
        assertFalse(MpvPlayerEngine.isConfiguredDv7Hdr10Fallback(
                details(5), true, true));
        assertFalse(MpvPlayerEngine.isConfiguredDv7Hdr10Fallback(
                details(7), true, false));
    }

    private static MpvPlayer.VideoTrackDiagnostics details(int profile) {
        return new MpvPlayer.VideoTrackDiagnostics(
                "dvhe.0" + profile + ".06", profile, 6,
                "hevc", "c2.qti.hevc.decoder", null);
    }
}
