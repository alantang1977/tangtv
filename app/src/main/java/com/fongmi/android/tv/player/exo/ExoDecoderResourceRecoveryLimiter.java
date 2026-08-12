package com.fongmi.android.tv.player.exo;

import androidx.media3.common.PlaybackException;

/** Limits automatic EXO recovery after Android reclaims decoder resources. */
public final class ExoDecoderResourceRecoveryLimiter {

    private boolean requested;

    public void reset() {
        requested = false;
    }

    public Action request(
            int errorCode,
            boolean exo,
            boolean playerAvailable,
            boolean mediaAvailable,
            boolean foreground) {
        if (errorCode
                != PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED) {
            return Action.NOT_RESOURCE_RECLAIM;
        }
        if (!exo) return Action.NOT_EXO;
        if (!playerAvailable || !mediaAvailable) return Action.STATE_UNAVAILABLE;
        if (requested) return Action.EXHAUSTED;
        requested = true;
        return foreground
                ? Action.RECOVER_NOW
                : Action.DEFER_UNTIL_FOREGROUND;
    }

    public enum Action {
        RECOVER_NOW,
        DEFER_UNTIL_FOREGROUND,
        NOT_RESOURCE_RECLAIM,
        NOT_EXO,
        STATE_UNAVAILABLE,
        EXHAUSTED
    }
}
