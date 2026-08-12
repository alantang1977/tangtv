package com.fongmi.android.tv.player.engine;

import androidx.media3.common.PlaybackException;

import tv.danmaku.ijk.media.player.IMediaPlayer;

final class IjkErrorMappingPolicy {

    private IjkErrorMappingPolicy() {
    }

    static int resolve(
            int what,
            int nativeError,
            boolean prepared,
            IjkPlayerEngine.OpenStage stage,
            int httpStatus) {
        if (httpStatus >= 400) {
            return PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS;
        }
        int direct = directErrorCode(what);
        if (direct != PlaybackException.ERROR_CODE_UNSPECIFIED) return direct;
        if (nativeError == -110) return PlaybackException.ERROR_CODE_TIMEOUT;
        if (prepared) return PlaybackException.ERROR_CODE_UNSPECIFIED;
        IjkPlayerEngine.OpenStage current = stage == null
                ? IjkPlayerEngine.OpenStage.NONE : stage;
        return switch (current) {
            case HTTP_OPENING ->
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED;
            case HTTP_OPENED, INPUT_OPENED, STREAM_INFO ->
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED;
            case COMPONENT_OPENED ->
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED;
            default -> PlaybackException.ERROR_CODE_UNSPECIFIED;
        };
    }

    private static int directErrorCode(int what) {
        return switch (what) {
            case IMediaPlayer.MEDIA_ERROR_IO ->
                    PlaybackException.ERROR_CODE_IO_UNSPECIFIED;
            case IMediaPlayer.MEDIA_ERROR_MALFORMED ->
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED;
            case IMediaPlayer.MEDIA_ERROR_UNSUPPORTED ->
                    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED;
            case IMediaPlayer.MEDIA_ERROR_TIMED_OUT ->
                    PlaybackException.ERROR_CODE_TIMEOUT;
            default -> PlaybackException.ERROR_CODE_UNSPECIFIED;
        };
    }
}
