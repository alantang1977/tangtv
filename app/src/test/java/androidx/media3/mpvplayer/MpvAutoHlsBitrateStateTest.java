package androidx.media3.mpvplayer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MpvAutoHlsBitrateStateTest {

    @Test
    public void automaticPerformancePriorityCanStageNumericCeiling() {
        MpvAutoHlsBitrateState state = new MpvAutoHlsBitrateState();

        assertTrue(state.stage(true, true, "15000000"));
        state.recordAccepted("15000000");

        MpvAutoHlsBitrateState.Snapshot snapshot = state.snapshot();
        assertEquals("15000000", snapshot.stagedOption());
        assertEquals("15000000", snapshot.acceptedOption());
        assertEquals(0, snapshot.observedCount());
    }

    @Test
    public void manualModeConfigPriorityAndInvalidValuesClearAutomaticState() {
        MpvAutoHlsBitrateState state = new MpvAutoHlsBitrateState();
        assertTrue(state.stage(true, true, "8000000"));

        assertFalse(state.stage(false, true, "4000000"));
        assertFalse(state.snapshot().staged());
        assertFalse(state.stage(true, false, "4000000"));
        assertFalse(state.stage(true, true, "2147483648"));
        assertFalse(state.snapshot().staged());
    }

    @Test
    public void nativeReadbackIsCountedSeparatelyFromAcceptedSetter() {
        MpvAutoHlsBitrateState state = new MpvAutoHlsBitrateState();
        assertTrue(state.stage(true, true, "12000000"));
        state.recordAccepted("12000000");

        assertTrue(state.recordObserved("hls-bitrate", 12_000_000L));
        assertFalse(state.recordObserved("hls-bitrate", 12_000_000L));
        assertFalse(state.recordObserved("demuxer-max-bytes", 12_000_000L));

        MpvAutoHlsBitrateState.Snapshot snapshot = state.snapshot();
        assertEquals(12_000_000L, snapshot.observedBitsPerSecond());
        assertEquals(2, snapshot.observedCount());
    }

    @Test
    public void nativeValueWithoutAcceptedAutomaticSetterIsNotClaimed() {
        MpvAutoHlsBitrateState state = new MpvAutoHlsBitrateState();
        assertTrue(state.stage(true, true, "12000000"));

        assertFalse(state.recordObserved("hls-bitrate", 12_000_000L));
        assertEquals(0, state.snapshot().observedCount());
    }

    @Test
    public void contextReleasePreservesStagedTargetButClearsNativeClaims() {
        MpvAutoHlsBitrateState state = new MpvAutoHlsBitrateState();
        assertTrue(state.stage(true, true, "6000000"));
        state.recordAccepted("6000000");
        state.recordObserved("hls-bitrate", 6_000_000L);

        state.onNativeContextReleased();

        MpvAutoHlsBitrateState.Snapshot snapshot = state.snapshot();
        assertEquals("6000000", snapshot.stagedOption());
        assertEquals("", snapshot.acceptedOption());
        assertEquals(-1, snapshot.observedBitsPerSecond());
        assertEquals(0, snapshot.observedCount());
    }

    @Test
    public void symbolicManualCompatibleValuesRemainValidForRollback() {
        MpvAutoHlsBitrateState state = new MpvAutoHlsBitrateState();

        assertTrue(state.stage(true, true, "min"));
        assertTrue(state.stage(true, true, "max"));
        assertTrue(state.stage(true, true, "no"));
    }
}
