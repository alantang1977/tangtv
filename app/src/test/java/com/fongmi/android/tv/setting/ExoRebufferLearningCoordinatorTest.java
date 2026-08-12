package com.fongmi.android.tv.setting;

import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.PlaybackNetworkIdentityPolicy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExoRebufferLearningCoordinatorTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final String TRACE_A = "p-1-1";
    private static final String TRACE_B = "p-2-1";

    @Test
    public void matchingEntrySeedsOnlyItsSession() {
        Fixture fixture = new Fixture();
        ExoRebufferLearningKey.Key key = key(1);
        fixture.store.record(
                key, 5, 30_000, 300_000, 10_000_000, 9_000_000, NOW);

        ExoRebufferLearningCoordinator.BeginResult result =
                fixture.coordinator.begin(TRACE_A, key, NOW + 1);

        assertEquals(ExoRebufferLearningCoordinator.Action.HIT, result.action());
        assertEquals(15_000, result.rebufferMs());
        assertEquals(15_000, fixture.coordinator.currentRebufferMs());
        assertEquals(1, result.sampleCount());
    }

    @Test
    public void runtimeRaiseIsNotPersistedUntilSessionFinishes() {
        Fixture fixture = new Fixture();
        ExoRebufferLearningKey.Key key = key(1);
        fixture.coordinator.begin(TRACE_A, key, NOW);

        ExoRebufferLearningCoordinator.UpdateResult update =
                fixture.coordinator.update(
                        TRACE_A,
                        5,
                        30_000,
                        30_000,
                        10_000_000,
                        9_000_000);

        assertTrue(update.changed());
        assertEquals(15_000, update.rebufferMs());
        assertFalse(fixture.store.lookup(key, NOW).hit());

        ExoRebufferLearningCoordinator.FinishResult finish =
                fixture.coordinator.finish(
                        TRACE_A,
                        key.networkDigest(),
                        5,
                        30_000,
                        30_000,
                        10_000_000,
                        9_000_000,
                        NOW + 1);

        assertEquals(ExoRebufferLearningCoordinator.Action.RECORDED, finish.action());
        assertEquals(15_000, fixture.store.lookup(key, NOW + 1).rebufferMs());
    }

    @Test
    public void networkSwitchDiscardsMixedSessionLearning() {
        Fixture fixture = new Fixture();
        ExoRebufferLearningKey.Key key = key(1);
        ExoRebufferLearningKey.Key switched = key(2);
        fixture.coordinator.begin(TRACE_A, key, NOW);

        assertEquals(
                ExoRebufferLearningCoordinator.Action.SKIP_UNSTABLE,
                fixture.coordinator.bind(TRACE_A, switched, NOW + 1).action());
        assertEquals(
                ExoRebufferLearningCoordinator.Action.SKIP_UNSTABLE,
                fixture.coordinator.bind(TRACE_A, key, NOW + 2).action());

        ExoRebufferLearningCoordinator.FinishResult result =
                fixture.coordinator.finish(
                        TRACE_A,
                        key.networkDigest(),
                        5,
                        30_000,
                        300_000,
                        10_000_000,
                        9_000_000,
                        NOW + 3);

        assertEquals(
                ExoRebufferLearningCoordinator.Action.SKIP_UNSTABLE,
                result.action());
        assertFalse(fixture.store.lookup(key, NOW + 3).hit());

        fixture.coordinator.begin(TRACE_B, key, NOW + 4);
        assertEquals(
                ExoRebufferLearningCoordinator.Action.SKIP_NETWORK_CHANGED,
                fixture.coordinator.finish(
                        TRACE_B,
                        switched.networkDigest(),
                        5,
                        30_000,
                        300_000,
                        10_000_000,
                        9_000_000,
                        NOW + 5).action());
    }

    @Test
    public void lateKeyCanApplyStoredPriorBeforeRuntimeEvidence() {
        Fixture fixture = new Fixture();
        ExoRebufferLearningKey.Key key = key(1);
        fixture.store.record(
                key, 5, 30_000, 300_000, 10_000_000, 9_000_000, NOW);
        fixture.coordinator.begin(TRACE_A, null, NOW + 1);

        ExoRebufferLearningCoordinator.BindResult bound =
                fixture.coordinator.bind(TRACE_A, key, NOW + 1);

        assertEquals(
                ExoRebufferLearningCoordinator.Action.LATE_HIT,
                bound.action());
        assertEquals(15_000, fixture.coordinator.currentRebufferMs());

        fixture.coordinator.finish(
                TRACE_A,
                key.networkDigest(),
                0,
                0,
                60_000,
                10_000_000,
                30_000_000,
                NOW + 2);
        assertEquals(15_000, fixture.store.lookup(key, NOW + 2).rebufferMs());
        assertEquals(2, fixture.store.lookup(key, NOW + 2).sampleCount());
    }

    @Test
    public void lateStoredPriorCannotOverwriteCurrentRuntimeEvidence() {
        Fixture fixture = new Fixture();
        ExoRebufferLearningKey.Key key = key(1);
        fixture.store.record(
                key, 3, 15_000, 300_000, 10_000_000, 11_000_000, NOW);
        fixture.coordinator.begin(TRACE_A, null, NOW + 1);
        fixture.coordinator.update(
                TRACE_A,
                0,
                0,
                60_000,
                10_000_000,
                30_000_000);

        ExoRebufferLearningCoordinator.BindResult bound =
                fixture.coordinator.bind(TRACE_A, key, NOW + 2);

        assertEquals(
                ExoRebufferLearningCoordinator.Action.LATE_BOUND,
                bound.action());
        assertEquals(3_000, fixture.coordinator.currentRebufferMs());
    }

    @Test
    public void changingPathOrProtocolMarksSessionUnstable() {
        Fixture fixture = new Fixture();
        ExoRebufferLearningKey.Key initial = key(1);
        ExoRebufferLearningKey.Key changed = new ExoRebufferLearningKey.Key(
                initial.networkDigest(),
                PlaybackAutoContext.PathKind.REMOTE,
                PlaybackAutoContext.Protocol.DASH,
                PlaybackAutoContext.StreamKind.VOD);
        fixture.coordinator.begin(TRACE_A, initial, NOW);

        assertEquals(
                ExoRebufferLearningCoordinator.Action.SKIP_UNSTABLE,
                fixture.coordinator.bind(TRACE_A, changed, NOW + 1).action());
        ExoRebufferLearningCoordinator.FinishResult finish =
                fixture.coordinator.finish(
                        TRACE_A,
                        initial.networkDigest(),
                        5,
                        30_000,
                        300_000,
                        10_000_000,
                        9_000_000,
                        NOW + 1);

        assertEquals(
                ExoRebufferLearningCoordinator.Action.SKIP_UNSTABLE,
                finish.action());
        assertFalse(fixture.store.lookup(initial, NOW + 1).hit());
        assertFalse(fixture.store.lookup(changed, NOW + 1).hit());
    }

    @Test
    public void finishingOldTraceCannotResetNewActiveSession() {
        Fixture fixture = new Fixture();
        ExoRebufferLearningKey.Key first = key(1);
        ExoRebufferLearningKey.Key second = key(2);
        fixture.store.record(
                second, 3, 15_000, 300_000, 10_000_000, 11_000_000, NOW);
        fixture.coordinator.begin(TRACE_A, first, NOW + 1);
        fixture.coordinator.begin(TRACE_B, second, NOW + 2);
        assertEquals(8_000, fixture.coordinator.currentRebufferMs());

        fixture.coordinator.finish(
                TRACE_A,
                first.networkDigest(),
                0,
                0,
                60_000,
                10_000_000,
                30_000_000,
                NOW + 3);

        assertEquals(8_000, fixture.coordinator.currentRebufferMs());
    }

    @Test
    public void unknownKeyNeverCreatesGlobalFallbackEntry() {
        Fixture fixture = new Fixture();
        fixture.coordinator.begin(TRACE_A, null, NOW);

        ExoRebufferLearningCoordinator.FinishResult result =
                fixture.coordinator.finish(
                        TRACE_A,
                        "",
                        5,
                        30_000,
                        300_000,
                        10_000_000,
                        9_000_000,
                        NOW + 1);

        assertEquals(
                ExoRebufferLearningCoordinator.Action.SKIP_UNKNOWN,
                result.action());
        assertEquals(0, fixture.store.entryCount(NOW + 1));
    }

    private static ExoRebufferLearningKey.Key key(int handle) {
        return new ExoRebufferLearningKey.Key(
                PlaybackNetworkIdentityPolicy.digest(handle),
                PlaybackAutoContext.PathKind.REMOTE,
                PlaybackAutoContext.Protocol.HLS,
                PlaybackAutoContext.StreamKind.VOD);
    }

    private static final class Fixture {

        private final ExoRebufferLearningStore store;
        private final ExoRebufferLearningCoordinator coordinator;

        private Fixture() {
            store = new ExoRebufferLearningStore(new MemoryBackend());
            coordinator = new ExoRebufferLearningCoordinator(store);
        }
    }

    private static final class MemoryBackend
            implements ExoRebufferLearningStore.Backend {

        private String value = "";

        @Override
        public String read() {
            return value;
        }

        @Override
        public void write(String value) {
            this.value = value == null ? "" : value;
        }

        @Override
        public void clear() {
            value = "";
        }
    }
}
