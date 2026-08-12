package com.fongmi.android.tv.player;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaybackExperimentCoordinatorTest {

    @Test
    public void invalidationRejectsQueuedToken() {
        PlaybackExperimentCoordinator coordinator =
                new PlaybackExperimentCoordinator();
        PlaybackExperimentCoordinator.Token token = coordinator.capture(
                PlaybackExperimentPolicy.Action.EXO_FRAME_SCHEDULING_AB);

        assertTrue(coordinator.isCurrent(token));
        PlaybackExperimentCoordinator.Update update = coordinator.invalidate(
                PlaybackExperimentCoordinator.Change.ROLLBACK);

        assertFalse(coordinator.isCurrent(token));
        assertEquals(PlaybackExperimentCoordinator.Change.ROLLBACK,
                update.change());
        assertEquals(update.generation(), coordinator.generation());
    }

    @Test
    public void internalExperimentInvalidationDoesNotCancelProductionAutomation() {
        PlaybackExperimentCoordinator coordinator =
                new PlaybackExperimentCoordinator();
        PlaybackExperimentCoordinator.Token token = coordinator.capture(
                PlaybackExperimentPolicy.Action.MPV_AUTO_PRELOAD);

        coordinator.invalidate(
                PlaybackExperimentCoordinator.Change.ROLLBACK);

        assertTrue(coordinator.isCurrent(token));
    }

    @Test
    public void listenersReceiveMonotonicGenerationAndCanUnregister() {
        PlaybackExperimentCoordinator coordinator =
                new PlaybackExperimentCoordinator();
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<PlaybackExperimentCoordinator.Update> last =
                new AtomicReference<>();
        PlaybackExperimentCoordinator.Registration registration =
                coordinator.addListener(update -> {
                    calls.incrementAndGet();
                    last.set(update);
                });

        long before = coordinator.generation();
        coordinator.invalidate(
                PlaybackExperimentCoordinator.Change.POLICY_CHANGED);
        assertEquals(1, calls.get());
        assertTrue(last.get().generation() > before);

        registration.close();
        registration.close();
        coordinator.invalidate(PlaybackExperimentCoordinator.Change.ROLLBACK);
        assertEquals(1, calls.get());
    }
}
