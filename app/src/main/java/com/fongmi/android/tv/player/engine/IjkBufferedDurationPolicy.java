package com.fongmi.android.tv.player.engine;

/** Conservative playable-duration estimate for IJK's native audio/video queues. */
final class IjkBufferedDurationPolicy {

    private IjkBufferedDurationPolicy() {
    }

    static long resolve(
            boolean hasAudioTrack,
            boolean hasVideoTrack,
            long audioDurationMs,
            long videoDurationMs) {
        long audio = Math.max(0, audioDurationMs);
        long video = Math.max(0, videoDurationMs);
        if (hasAudioTrack && hasVideoTrack) return Math.min(audio, video);
        if (hasAudioTrack) return audio;
        if (hasVideoTrack) return video;
        return audio > 0 && video > 0 ? Math.min(audio, video) : 0;
    }

    static long bufferedPosition(
            long positionMs,
            long durationMs,
            int bufferingPercent,
            long nativeBufferedDurationMs,
            long nativeBufferedPositionMs) {
        long position = Math.max(0, positionMs);
        if (durationMs <= 0) return position;
        int percent = Math.clamp(bufferingPercent, 0, 100);
        long percentEnd = durationMs / 100 * percent
                + durationMs % 100 * percent / 100;
        long nativeEnd = saturatedAdd(position, Math.max(0, nativeBufferedDurationMs));
        long reportedEnd = Math.max(position, nativeBufferedPositionMs);
        return Math.min(durationMs, Math.max(
                Math.max(position, reportedEnd), Math.max(percentEnd, nativeEnd)));
    }

    private static long saturatedAdd(long first, long second) {
        return second > 0 && first > Long.MAX_VALUE - second
                ? Long.MAX_VALUE : first + second;
    }
}
