package com.fongmi.android.tv.player.exo;

import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.PlaybackAutoContextStore;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExoPreloadTrafficCoordinatorTest {

    @Test
    public void customAndMedia3PreloadsShareOneSessionCount() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        ExoPreloadTrafficCoordinator coordinator =
                new ExoPreloadTrafficCoordinator(store);
        PlaybackAutoContext.SessionToken session =
                ExoThroughputCoordinatorTest.beginExoSession(
                        store, "p-preload1-1", 0);

        ExoPreloadTrafficCoordinator.Registration custom = coordinator.acquire(
                session, ExoPreloadTrafficCoordinator.Source.CUSTOM);
        ExoPreloadTrafficCoordinator.Registration media3 = coordinator.acquire(
                session, ExoPreloadTrafficCoordinator.Source.MEDIA3);

        assertTrue(coordinator.isActive(session));
        assertEquals(1, coordinator.snapshot(session).customCount());
        assertEquals(1, coordinator.snapshot(session).media3Count());
        assertEquals(2, coordinator.snapshot(session).totalCount());

        custom.close();
        assertTrue(coordinator.isActive(session));
        media3.close();
        assertFalse(coordinator.isActive(session));
    }

    @Test
    public void duplicateCloseAndSessionReplacementCannotLeakContention() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        ExoPreloadTrafficCoordinator coordinator =
                new ExoPreloadTrafficCoordinator(store);
        PlaybackAutoContext.SessionToken first =
                ExoThroughputCoordinatorTest.beginExoSession(
                        store, "p-preload2-1", 0);
        ExoPreloadTrafficCoordinator.Registration registration = coordinator.acquire(
                first, ExoPreloadTrafficCoordinator.Source.CUSTOM);

        PlaybackAutoContext.SessionToken second =
                ExoThroughputCoordinatorTest.beginExoSession(
                        store, "p-preload3-1", 1_000);

        assertFalse(coordinator.isActive(first));
        assertFalse(coordinator.isActive(second));
        registration.close();
        registration.close();
        assertEquals(0, coordinator.snapshot(second).totalCount());
    }

    @Test
    public void staleTraceCannotRegisterAgainstCurrentSession() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        ExoPreloadTrafficCoordinator coordinator =
                new ExoPreloadTrafficCoordinator(store);
        PlaybackAutoContext.SessionToken session =
                ExoThroughputCoordinatorTest.beginExoSession(
                        store, "p-preload4-1", 0);

        ExoPreloadTrafficCoordinator.Registration stale = coordinator.acquire(
                "p-old-1", ExoPreloadTrafficCoordinator.Source.CUSTOM);

        assertFalse(stale.active());
        assertFalse(coordinator.isActive(session));
    }

    @Test
    public void sessionWithoutConfirmedExoKernelCannotRegisterContention() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        ExoPreloadTrafficCoordinator coordinator =
                new ExoPreloadTrafficCoordinator(store);
        PlaybackAutoContext.SessionToken session =
                store.beginSession("p-preload5-1", 0);

        ExoPreloadTrafficCoordinator.Registration registration = coordinator.acquire(
                session, ExoPreloadTrafficCoordinator.Source.CUSTOM);

        assertFalse(registration.active());
        assertFalse(coordinator.isActive(session));
    }
}
