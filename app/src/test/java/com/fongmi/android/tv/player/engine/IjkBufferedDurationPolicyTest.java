package com.fongmi.android.tv.player.engine;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class IjkBufferedDurationPolicyTest {

    @Test
    public void audioVideoUsesShorterPlayableQueue() {
        assertEquals(0, IjkBufferedDurationPolicy.resolve(
                true, true, 4_000, 0));
        assertEquals(2_000, IjkBufferedDurationPolicy.resolve(
                true, true, 4_000, 2_000));
    }

    @Test
    public void singleTrackUsesItsOwnQueue() {
        assertEquals(3_000, IjkBufferedDurationPolicy.resolve(
                true, false, 3_000, 8_000));
        assertEquals(5_000, IjkBufferedDurationPolicy.resolve(
                false, true, 9_000, 5_000));
    }

    @Test
    public void unknownTracksDoNotTrustOneSidedQueue() {
        assertEquals(0, IjkBufferedDurationPolicy.resolve(
                false, false, 3_000, 0));
        assertEquals(2_000, IjkBufferedDurationPolicy.resolve(
                false, false, 3_000, 2_000));
    }

    @Test
    public void bufferedPositionUsesNativeQueueWhenItIsAheadOfPercent() {
        assertEquals(35_000, IjkBufferedDurationPolicy.bufferedPosition(
                20_000, 100_000, 25, 15_000, 0));
        assertEquals(50_000, IjkBufferedDurationPolicy.bufferedPosition(
                20_000, 100_000, 50, 15_000, 0));
    }

    @Test
    public void bufferedPositionUsesPreciseNativeEndpointBeforePercentChanges() {
        assertEquals(42_500, IjkBufferedDurationPolicy.bufferedPosition(
                20_000, 7_200_000, 0, 4_000, 42_500));
    }

    @Test
    public void bufferedPositionStaysBounded() {
        assertEquals(100_000, IjkBufferedDurationPolicy.bufferedPosition(
                90_000, 100_000, 100, 30_000, 130_000));
        assertEquals(12_000, IjkBufferedDurationPolicy.bufferedPosition(
                12_000, -1, 100, 30_000, 90_000));
    }
}
