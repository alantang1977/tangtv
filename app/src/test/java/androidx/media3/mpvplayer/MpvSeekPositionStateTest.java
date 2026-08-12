package androidx.media3.mpvplayer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MpvSeekPositionStateTest {

    @Test
    public void staleNativePositionsCannotOverwriteSeekTarget() {
        MpvSeekPositionState state = new MpvSeekPositionState();
        state.begin(420_000, 1_000);

        assertEquals(420_000, state.resolve(180_000, 1_100));
        assertEquals(420_000, state.resolve(660_000, 1_200));
        assertTrue(state.hasTarget());
    }

    @Test
    public void targetReleasesOnlyAfterNativePositionArrivesNearby() {
        MpvSeekPositionState state = new MpvSeekPositionState();
        state.begin(420_000, 1_000);

        assertEquals(418_500, state.resolve(418_500, 2_000));
        assertFalse(state.hasTarget());
    }

    @Test
    public void timeoutPreventsPermanentLatchAndConsecutiveSeekReplacesTarget() {
        MpvSeekPositionState state = new MpvSeekPositionState();
        state.begin(420_000, 1_000);
        state.begin(120_000, 2_000);

        assertEquals(120_000, state.resolve(420_000, 2_100));
        assertEquals(420_000, state.resolve(
                420_000, 2_000 + MpvSeekPositionState.TARGET_TIMEOUT_MS));
        assertFalse(state.hasTarget());
    }
}
