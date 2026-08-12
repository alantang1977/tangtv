package com.fongmi.android.tv.player.exo;

import com.fongmi.android.tv.player.PlaybackAutoContext;
import com.fongmi.android.tv.player.PlaybackAutoContextStore;
import com.fongmi.android.tv.player.PlaybackSystemConditionCoordinator;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ExoPreloadSystemConditionBridgeTest {

    @Test
    public void forwardsOnlyTheExpectedCompleteSessionToken() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        PlaybackAutoContext.SessionToken first = store.beginSession("p-same-1", 0);
        PlaybackSystemConditionCoordinator coordinator =
                new PlaybackSystemConditionCoordinator(store);
        assertTrue(coordinator.beginSession(first));
        AtomicInteger callbacks = new AtomicInteger();
        ExoPreloadSystemConditionBridge bridge = new ExoPreloadSystemConditionBridge(
                first, coordinator, update -> callbacks.incrementAndGet());

        assertTrue(publish(coordinator, first, 1));
        PlaybackAutoContext.SessionToken replacement =
                store.beginSession("p-same-1", 2);
        assertTrue(coordinator.endSession(first));
        assertTrue(coordinator.beginSession(replacement));
        assertTrue(publish(coordinator, replacement, 3));

        assertEquals(1, callbacks.get());
        bridge.close();
    }

    @Test
    public void closeIsIdempotentAndStopsFurtherCallbacks() {
        PlaybackAutoContextStore store = new PlaybackAutoContextStore();
        PlaybackAutoContext.SessionToken session = store.beginSession("p-bridge-1", 0);
        PlaybackSystemConditionCoordinator coordinator =
                new PlaybackSystemConditionCoordinator(store);
        assertTrue(coordinator.beginSession(session));
        AtomicInteger callbacks = new AtomicInteger();
        ExoPreloadSystemConditionBridge bridge = new ExoPreloadSystemConditionBridge(
                session, coordinator, update -> callbacks.incrementAndGet());

        assertTrue(publish(coordinator, session, 1));
        bridge.close();
        bridge.close();
        assertTrue(publish(coordinator, session, 2));

        assertEquals(1, callbacks.get());
    }

    @Test
    public void networkAndPressureCallbacksRequestImmediateDisruption() {
        PlaybackAutoContext.SessionToken session =
                new PlaybackAutoContext.SessionToken("p-disrupt-1", 1);
        PlaybackSystemConditionCoordinator.Update network = update(
                session,
                PlaybackAutoContext.SystemConditionTrigger.NETWORK_CALLBACK,
                PlaybackAutoContext.Fact.withTtl(
                        new PlaybackAutoContext.NetworkSnapshot(
                                true, true, false, false,
                                PlaybackAutoContext.NetworkTransport.WIFI,
                                PlaybackAutoContext.DataSaverState.DISABLED),
                        PlaybackAutoContext.ValueSource.SYSTEM_CALLBACK,
                        PlaybackAutoContext.Confidence.HIGH,
                        0,
                        100),
                null,
                null);
        PlaybackSystemConditionCoordinator.Update thermal = update(
                session,
                PlaybackAutoContext.SystemConditionTrigger.THERMAL_CALLBACK,
                null,
                null,
                PlaybackAutoContext.Fact.withTtl(
                        PlaybackAutoContext.ThermalState.MODERATE,
                        PlaybackAutoContext.ValueSource.SYSTEM_CALLBACK,
                        PlaybackAutoContext.Confidence.HIGH,
                        0,
                        100));

        assertEquals(
                AutoPreloadPolicy.Reason.NETWORK_CHANGED,
                ExoPreloadSystemConditionBridge.disruption(network, 1));
        assertEquals(
                AutoPreloadPolicy.Reason.THERMAL_MODERATE,
                ExoPreloadSystemConditionBridge.disruption(thermal, 1));
        assertNull(ExoPreloadSystemConditionBridge.disruption(thermal, 100));
    }

    private static PlaybackSystemConditionCoordinator.Update update(
            PlaybackAutoContext.SessionToken session,
            PlaybackAutoContext.SystemConditionTrigger trigger,
            PlaybackAutoContext.Fact<PlaybackAutoContext.NetworkSnapshot> network,
            PlaybackAutoContext.Fact<PlaybackAutoContext.PowerState> power,
            PlaybackAutoContext.Fact<PlaybackAutoContext.ThermalState> thermal) {
        return new PlaybackSystemConditionCoordinator.Update(
                session,
                trigger,
                thermal,
                power,
                null,
                network,
                0);
    }

    private static boolean publish(
            PlaybackSystemConditionCoordinator coordinator,
            PlaybackAutoContext.SessionToken session,
            long nowMs) {
        return coordinator.publish(
                session,
                PlaybackAutoContext.SystemConditionTrigger.NETWORK_CALLBACK,
                new PlaybackAutoContext.NetworkSnapshot(
                        true,
                        true,
                        false,
                        false,
                        PlaybackAutoContext.NetworkTransport.WIFI,
                        PlaybackAutoContext.DataSaverState.DISABLED),
                false,
                null,
                35,
                nowMs);
    }
}
