package com.fongmi.android.tv.player.exo;

import androidx.media3.common.PlaybackException;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ExoDecoderResourceRecoveryLimiterTest {

    @Test
    public void foregroundResourceReclaimRecoversImmediatelyOnce() {
        ExoDecoderResourceRecoveryLimiter limiter =
                new ExoDecoderResourceRecoveryLimiter();

        assertEquals(
                ExoDecoderResourceRecoveryLimiter.Action.RECOVER_NOW,
                limiter.request(resourceReclaimed(), true, true, true, true));
        assertEquals(
                ExoDecoderResourceRecoveryLimiter.Action.EXHAUSTED,
                limiter.request(resourceReclaimed(), true, true, true, true));
    }

    @Test
    public void backgroundResourceReclaimDefersUntilForeground() {
        ExoDecoderResourceRecoveryLimiter limiter =
                new ExoDecoderResourceRecoveryLimiter();

        assertEquals(
                ExoDecoderResourceRecoveryLimiter.Action.DEFER_UNTIL_FOREGROUND,
                limiter.request(resourceReclaimed(), true, true, true, false));
        assertEquals(
                ExoDecoderResourceRecoveryLimiter.Action.EXHAUSTED,
                limiter.request(resourceReclaimed(), true, true, true, false));
    }

    @Test
    public void resetStartsANewPlaybackBudget() {
        ExoDecoderResourceRecoveryLimiter limiter =
                new ExoDecoderResourceRecoveryLimiter();
        limiter.request(resourceReclaimed(), true, true, true, true);

        limiter.reset();

        assertEquals(
                ExoDecoderResourceRecoveryLimiter.Action.RECOVER_NOW,
                limiter.request(resourceReclaimed(), true, true, true, true));
    }

    @Test
    public void unrelatedRequestsDoNotConsumeRecoveryBudget() {
        ExoDecoderResourceRecoveryLimiter limiter =
                new ExoDecoderResourceRecoveryLimiter();

        assertEquals(
                ExoDecoderResourceRecoveryLimiter.Action.NOT_RESOURCE_RECLAIM,
                limiter.request(
                        PlaybackException.ERROR_CODE_DECODING_FAILED,
                        true,
                        true,
                        true,
                        true));
        assertEquals(
                ExoDecoderResourceRecoveryLimiter.Action.NOT_EXO,
                limiter.request(resourceReclaimed(), false, true, true, true));
        assertEquals(
                ExoDecoderResourceRecoveryLimiter.Action.STATE_UNAVAILABLE,
                limiter.request(resourceReclaimed(), true, false, true, true));
        assertEquals(
                ExoDecoderResourceRecoveryLimiter.Action.STATE_UNAVAILABLE,
                limiter.request(resourceReclaimed(), true, true, false, true));
        assertEquals(
                ExoDecoderResourceRecoveryLimiter.Action.RECOVER_NOW,
                limiter.request(resourceReclaimed(), true, true, true, true));
    }

    private static int resourceReclaimed() {
        return PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED;
    }
}
