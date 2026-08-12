package com.fongmi.android.tv.player.exo;

/** Conservative gate for requesting Media3 audio/video tunneling. */
public final class ExoTunnelingPolicy {

    private ExoTunnelingPolicy() {
    }

    public static Decision resolve(Request request) {
        if (request == null || !request.requested()) return Decision.disabled("user-off");
        if (request.fallbackAttempted()) return Decision.disabled("fallback-already-attempted");
        if (!request.surfaceView()) return Decision.disabled("requires-surface-view");
        if (!request.hardDecode()) return Decision.disabled("requires-hard-decode");
        if (!request.hasAudio() || !request.hasVideo()) return Decision.disabled("requires-audio-video");
        if (request.videoEffectsActive()) return Decision.disabled("video-effects-active");
        if (request.externalAudio()) return Decision.disabled("external-audio-device");
        if (request.blacklisted()) return Decision.disabled("device-codec-blacklisted");
        if (request.automatic() && !request.televisionDevice()) return Decision.disabled("automatic-tv-only");
        return new Decision(true, "eligible");
    }

    public record Request(boolean requested, boolean surfaceView, boolean hardDecode, boolean hasAudio, boolean hasVideo, boolean videoEffectsActive, boolean externalAudio, boolean blacklisted, boolean automatic, boolean televisionDevice, boolean fallbackAttempted) {
    }

    public record Decision(boolean enabled, String reason) {
        private static Decision disabled(String reason) {
            return new Decision(false, reason);
        }
    }
}
