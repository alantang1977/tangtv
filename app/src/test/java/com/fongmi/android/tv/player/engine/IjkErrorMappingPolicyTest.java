package com.fongmi.android.tv.player.engine;

import androidx.media3.common.PlaybackException;

import org.junit.Test;

import tv.danmaku.ijk.media.player.IMediaPlayer;

import static org.junit.Assert.assertEquals;

public class IjkErrorMappingPolicyTest {

    @Test
    public void mapsSuccessfulHttpOpenFailureToContainerParsing() {
        int code = IjkErrorMappingPolicy.resolve(
                -10_000,
                0,
                false,
                IjkPlayerEngine.OpenStage.HTTP_OPENED,
                200);

        assertEquals(
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
                code);
    }

    @Test
    public void mapsComponentOpenFailureToDecoderInitialization() {
        int code = IjkErrorMappingPolicy.resolve(
                -10_000,
                0,
                false,
                IjkPlayerEngine.OpenStage.COMPONENT_OPENED,
                206);

        assertEquals(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, code);
    }

    @Test
    public void preservesDirectIjkTimeout() {
        int code = IjkErrorMappingPolicy.resolve(
                IMediaPlayer.MEDIA_ERROR_TIMED_OUT,
                0,
                false,
                IjkPlayerEngine.OpenStage.HTTP_OPENING,
                0);

        assertEquals(PlaybackException.ERROR_CODE_TIMEOUT, code);
    }

    @Test
    public void mapsHttpFailureStatusBeforeStageInference() {
        int code = IjkErrorMappingPolicy.resolve(
                -10_000,
                0,
                false,
                IjkPlayerEngine.OpenStage.HTTP_OPENED,
                403);

        assertEquals(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, code);
    }

    @Test
    public void mapsHttpFailureStatusBeforeDirectIjkIoError() {
        int code = IjkErrorMappingPolicy.resolve(
                IMediaPlayer.MEDIA_ERROR_IO,
                0,
                false,
                IjkPlayerEngine.OpenStage.HTTP_OPENED,
                403);

        assertEquals(
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                code);
    }
}
