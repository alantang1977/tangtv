package com.fongmi.android.tv.player.exo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.media3.exoplayer.DefaultRenderersFactory;

import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.setting.PlayerSetting;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class ExoUtilTest {

    @Test
    public void getRenderMode_keepsPlatformRendererFirstForHardDecode() {
        assertEquals(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF, ExoUtil.getRenderMode(PlayerEngine.HARD));
    }

    @Test
    public void getRenderMode_prefersExtensionRendererForSoftDecode() {
        assertEquals(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER, ExoUtil.getRenderMode(PlayerEngine.SOFT));
    }

    @Test
    public void getFfmpegVideoRenderMode_keepsFfmpegAsFallbackForHardDecode() {
        assertEquals(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON, ExoUtil.getFfmpegVideoRenderMode(ExoUtil.getRenderMode(PlayerEngine.HARD)));
    }

    @Test
    public void getFfmpegVideoRenderMode_prefersFfmpegForSoftDecode() {
        assertEquals(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER, ExoUtil.getFfmpegVideoRenderMode(ExoUtil.getRenderMode(PlayerEngine.SOFT)));
    }

    @Test
    public void hardDecodeFactory_keepsFfmpegVideoFallbackWired() throws Exception {
        String source = readMainSource("player/exo/ExoUtil.java");
        int start = source.indexOf("private static class FfmpegRenderersFactory");
        int end = source.indexOf("private static class FfmpegFallbackRenderersFactory", start);
        String factory = source.substring(start, end);

        assertFalse(factory.contains("if (videoRenderMode == EXTENSION_RENDERER_MODE_OFF) return;"));
        assertTrue(factory.contains("out.add(index, buildFfmpegVideoRenderer("));
    }

    @Test
    public void automaticConstraintReasonLabel_avoidsUnsupportedAndroidStringBuilderApi() throws Exception {
        String source = readMainSource("player/exo/ExoAutomaticVideoConstraintPolicy.java");

        assertFalse(source.contains("builder.isEmpty()"));
    }

    @Test
    public void ffmpegRendererPolicy_usesFullNextLibRenderersInNextLibMode() {
        assertTrue(ExoUtil.useFfmpegAudioFallback(PlayerSetting.FFMPEG_MODE_NEXTLIB));
        assertTrue(ExoUtil.useFfmpegVideoRenderer(PlayerSetting.FFMPEG_MODE_NEXTLIB));
    }

    @Test
    public void ffmpegRendererPolicy_usesAudioAndVideoFallbackInSimpleMode() {
        assertTrue(ExoUtil.useFfmpegAudioFallback(PlayerSetting.FFMPEG_MODE_SIMPLE));
        assertTrue(ExoUtil.useFfmpegVideoRenderer(PlayerSetting.FFMPEG_MODE_SIMPLE));
    }

    @Test
    public void ffmpegRendererPolicy_disablesNextLibInOfficialMode() {
        assertFalse(ExoUtil.useFfmpegAudioFallback(PlayerSetting.FFMPEG_MODE_OFFICIAL));
        assertFalse(ExoUtil.useFfmpegVideoRenderer(PlayerSetting.FFMPEG_MODE_OFFICIAL));
    }

    private static String readMainSource(String relative) throws Exception {
        Path path = Path.of("app/src/main/java/com/fongmi/android/tv", relative);
        if (!Files.exists(path)) path = Path.of("src/main/java/com/fongmi/android/tv", relative);
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
