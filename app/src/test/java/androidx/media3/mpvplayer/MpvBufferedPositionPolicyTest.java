package androidx.media3.mpvplayer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MpvBufferedPositionPolicyTest {

    @Test
    public void usesFurthestValidNativeTimelineEndpoint() {
        assertEquals(180_000, MpvBufferedPositionPolicy.resolve(
                100_000, 600_000, 30_000, 180_000, false, false));
        assertEquals(220_000, MpvBufferedPositionPolicy.resolve(
                100_000, 600_000, 120_000, 180_000, false, false));
    }

    @Test
    public void rejectsStaleAndOutOfRangeCacheEndAfterSeek() {
        assertEquals(130_000, MpvBufferedPositionPolicy.resolve(
                100_000, 600_000, 30_000, 90_000, false, false));
        assertEquals(130_000, MpvBufferedPositionPolicy.resolve(
                100_000, 600_000, 30_000, 900_000, false, false));
    }

    @Test
    public void localFallbackDoesNotOverrideIsoOrUnknownDuration() {
        assertEquals(600_000, MpvBufferedPositionPolicy.resolve(
                100_000, 600_000, 0, 0, true, false));
        assertEquals(100_000, MpvBufferedPositionPolicy.resolve(
                100_000, 600_000, 0, 0, true, true));
        assertEquals(100_000, MpvBufferedPositionPolicy.resolve(
                100_000, -1, 30_000, 180_000, true, false));
    }
}
