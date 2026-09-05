package com.fongmi.android.tv.player;

import com.fongmi.android.tv.service.IntroSkipService;
import com.fongmi.android.tv.service.IntroSkipService.Segment;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IntroSkipPlaybackStateTest {

    @Test
    public void cancelingConfirmationAllowsTheSameSegmentToBeAskedAgain() {
        IntroSkipPlayback playback = new IntroSkipPlayback();
        Segment segment = segment();

        assertTrue(playback.beginConfirmation(segment));
        assertTrue(playback.isConfirmationPending(segment));

        playback.cancelConfirmation(segment);

        assertFalse(playback.isSegmentHandled(segment));
        assertFalse(playback.isConfirmationPending(segment));
        assertTrue(playback.beginConfirmation(segment));
    }

    @Test
    public void completingConfirmationMarksOnlyThatStableSegmentHandled() {
        IntroSkipPlayback playback = new IntroSkipPlayback();
        Segment segment = segment();

        assertTrue(playback.beginConfirmation(segment));
        playback.completeConfirmation(segment);

        assertTrue(playback.isSegmentHandled(segment));
        assertFalse(playback.isConfirmationPending(segment));
        assertFalse(playback.beginConfirmation(segment));
    }

    @Test
    public void unknownTrailingEndCannotAdvanceBeforeDurationIsKnown() {
        Segment segment = IntroSkipService.parseTheIntroDb(
                "{\"credits\":[{\"start_ms\":1380000,\"end_ms\":null}]}", 0)
                .getEndings().get(0);

        assertFalse(IntroSkipPlayback.endsWithFile(segment, 0));
        assertTrue(IntroSkipPlayback.endsWithFile(segment, 1_500_000));
    }

    private static Segment segment() {
        return IntroSkipService.parseTheIntroDb(
                "{\"intro\":[{\"start_ms\":0,\"end_ms\":45000}]}", 2_700_000)
                .getOpenings().get(0);
    }
}
