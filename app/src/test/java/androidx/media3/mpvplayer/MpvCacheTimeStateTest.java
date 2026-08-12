package androidx.media3.mpvplayer;

import com.fongmi.android.tv.player.PlaybackAutoContext;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MpvCacheTimeStateTest {

    @Test
    public void acceptedRuntimeTargetsAreVisibleButNotClaimedAsNativeReadback() {
        MpvCacheTimePolicy.Decision decision = remoteDecision(30, 2_000);
        MpvCacheTimeState state = state(decision);

        assertTrue(state.recordAccepted("cache-secs", "30"));
        assertTrue(state.recordAccepted("demuxer-readahead-secs", "1"));
        assertTrue(state.recordAccepted("demuxer-hysteresis-secs", "5"));

        MpvCacheTimeState.Snapshot snapshot = state.snapshot();
        assertEquals(30, snapshot.cacheSeconds());
        assertEquals(1, snapshot.readaheadSeconds());
        assertEquals(5, snapshot.hysteresisSeconds());
        assertEquals(0, snapshot.observedOptions());
    }

    @Test
    public void nativePropertyCallbacksOverrideRequestedValuesAndCountReadback() {
        MpvCacheTimeState state = state(remoteDecision(30, 2_000));

        assertTrue(state.recordObserved("cache-secs", 24.0));
        assertFalse(state.recordObserved("cache-secs", 24.0));
        assertTrue(state.recordObserved("demuxer-readahead-secs", 1.0));
        assertTrue(state.recordObserved("demuxer-hysteresis-secs", 4.0));

        MpvCacheTimeState.Snapshot snapshot = state.snapshot();
        assertEquals(24, snapshot.cacheSeconds());
        assertEquals(1, snapshot.readaheadSeconds());
        assertEquals(4, snapshot.hysteresisSeconds());
        assertEquals(3, snapshot.observedOptions());
    }

    @Test
    public void invalidOrUnrelatedPropertyValuesAreIgnored() {
        MpvCacheTimeState state = state(remoteDecision(30, 2_000));

        assertFalse(state.recordObserved("cache-secs", Double.NaN));
        assertFalse(state.recordObserved("cache-secs", "not-a-number"));
        assertFalse(state.recordObserved("demuxer-max-bytes", 64));

        assertEquals(0, state.snapshot().observedOptions());
        assertEquals(30, state.snapshot().cacheSeconds());
    }

    @Test
    public void aNewAcceptedDecisionClearsOldReadbackUntilNativeConfirmsIt() {
        MpvCacheTimeState state = state(remoteDecision(30, 2_000));
        state.recordObserved("demuxer-hysteresis-secs", 5.0);
        assertEquals(1, state.snapshot().observedOptions());

        MpvCacheTimePolicy.Decision guarded = MpvCacheTimePolicy.resolve(
                true,
                true,
                true,
                30,
                1,
                2_000,
                PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                PlaybackAutoContext.PathKind.EXTERNAL_LOOPBACK);
        state.select(guarded);
        assertTrue(state.recordAccepted("demuxer-hysteresis-secs", "0"));

        assertEquals(0, state.snapshot().hysteresisSeconds());
        assertEquals(0, state.snapshot().observedOptions());
        assertEquals(MpvCacheTimePolicy.Reason.PATH_GUARD, state.snapshot().reason());
    }

    @Test
    public void contextResetReturnsToSafeInitialValuesAndClearsReadback() {
        MpvCacheTimePolicy.Decision decision = remoteDecision(30, 2_000);
        MpvCacheTimeState state = state(decision);
        state.recordObserved("cache-secs", 24.0);
        state.recordObserved("demuxer-hysteresis-secs", 4.0);

        state.reset(decision, 30, 1, 0);

        MpvCacheTimeState.Snapshot snapshot = state.snapshot();
        assertEquals(30, snapshot.cacheSeconds());
        assertEquals(1, snapshot.readaheadSeconds());
        assertEquals(0, snapshot.hysteresisSeconds());
        assertEquals(0, snapshot.observedOptions());
    }

    private static MpvCacheTimeState state(MpvCacheTimePolicy.Decision decision) {
        return new MpvCacheTimeState(decision, 30, 1, 0);
    }

    private static MpvCacheTimePolicy.Decision remoteDecision(
            int cacheSeconds,
            int rebufferMs) {
        return MpvCacheTimePolicy.resolve(
                true,
                true,
                true,
                cacheSeconds,
                1,
                rebufferMs,
                PlaybackAutoContext.Protocol.PROGRESSIVE_HTTP,
                PlaybackAutoContext.PathKind.REMOTE);
    }
}
