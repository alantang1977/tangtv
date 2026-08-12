package com.fongmi.android.tv.player;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaybackSystemConditionPolicyTest {

    @Test
    public void roamingDominatesMeteredCost() {
        PlaybackSystemConditionPolicy.NetworkResult result =
                PlaybackSystemConditionPolicy.evaluateNetwork(snapshot(true, true, true, true));

        assertEquals(PlaybackAutoContext.NetworkCost.ROAMING, result.cost());
        assertEquals(PlaybackAutoContext.Confidence.HIGH, result.confidence());
        assertTrue(result.known());
    }

    @Test
    public void explicitNonRoamingMeteredStateHasHighConfidence() {
        PlaybackSystemConditionPolicy.NetworkResult metered =
                PlaybackSystemConditionPolicy.evaluateNetwork(snapshot(true, true, true, false));
        PlaybackSystemConditionPolicy.NetworkResult unmetered =
                PlaybackSystemConditionPolicy.evaluateNetwork(snapshot(true, true, false, false));

        assertEquals(PlaybackAutoContext.NetworkCost.METERED, metered.cost());
        assertEquals(PlaybackAutoContext.Confidence.HIGH, metered.confidence());
        assertEquals(PlaybackAutoContext.NetworkCost.UNMETERED, unmetered.cost());
        assertEquals(PlaybackAutoContext.Confidence.HIGH, unmetered.confidence());
    }

    @Test
    public void unknownRoamingLowersCostConfidenceAndUnavailableNetworkStaysUnknown() {
        PlaybackSystemConditionPolicy.NetworkResult vpn =
                PlaybackSystemConditionPolicy.evaluateNetwork(new PlaybackAutoContext.NetworkSnapshot(
                        true, true, true, null, PlaybackAutoContext.NetworkTransport.VPN,
                        PlaybackAutoContext.DataSaverState.DISABLED));
        PlaybackSystemConditionPolicy.NetworkResult unavailable =
                PlaybackSystemConditionPolicy.evaluateNetwork(new PlaybackAutoContext.NetworkSnapshot(
                        false, false, null, null, PlaybackAutoContext.NetworkTransport.UNKNOWN,
                        PlaybackAutoContext.DataSaverState.DISABLED));
        PlaybackSystemConditionPolicy.NetworkResult availabilityUnknown =
                PlaybackSystemConditionPolicy.evaluateNetwork(new PlaybackAutoContext.NetworkSnapshot(
                        null, null, false, false, PlaybackAutoContext.NetworkTransport.UNKNOWN,
                        PlaybackAutoContext.DataSaverState.UNKNOWN));

        assertEquals(PlaybackAutoContext.NetworkCost.METERED, vpn.cost());
        assertEquals(PlaybackAutoContext.Confidence.MEDIUM, vpn.confidence());
        assertFalse(unavailable.known());
        assertFalse(availabilityUnknown.known());
    }

    @Test
    public void networkSnapshotConfidenceReflectsEvidenceCompleteness() {
        PlaybackAutoContext.NetworkSnapshot complete = snapshot(true, true, false, false);
        PlaybackAutoContext.NetworkSnapshot partial = new PlaybackAutoContext.NetworkSnapshot(
                true, null, null, null, PlaybackAutoContext.NetworkTransport.VPN,
                PlaybackAutoContext.DataSaverState.UNKNOWN);

        assertEquals(PlaybackAutoContext.Confidence.HIGH,
                PlaybackSystemConditionPolicy.networkSnapshotConfidence(complete));
        assertEquals(PlaybackAutoContext.Confidence.MEDIUM,
                PlaybackSystemConditionPolicy.networkSnapshotConfidence(partial));
        assertEquals(PlaybackAutoContext.Confidence.UNKNOWN,
                PlaybackSystemConditionPolicy.networkSnapshotConfidence(
                        PlaybackAutoContext.NetworkSnapshot.unknown()));
    }

    @Test
    public void dataSaverRawStatusesMapWithoutGuessing() {
        assertEquals(PlaybackAutoContext.DataSaverState.DISABLED,
                PlaybackSystemConditionPolicy.dataSaverState(
                        PlaybackSystemConditionPolicy.DATA_SAVER_DISABLED));
        assertEquals(PlaybackAutoContext.DataSaverState.WHITELISTED,
                PlaybackSystemConditionPolicy.dataSaverState(
                        PlaybackSystemConditionPolicy.DATA_SAVER_WHITELISTED));
        assertEquals(PlaybackAutoContext.DataSaverState.ENABLED,
                PlaybackSystemConditionPolicy.dataSaverState(
                        PlaybackSystemConditionPolicy.DATA_SAVER_ENABLED));
        assertEquals(PlaybackAutoContext.DataSaverState.UNKNOWN,
                PlaybackSystemConditionPolicy.dataSaverState(99));
        assertEquals(PlaybackAutoContext.DataSaverState.UNKNOWN,
                PlaybackSystemConditionPolicy.dataSaverState(null));
    }

    @Test
    public void powerSaverMapsOnlyExplicitSystemValues() {
        assertEquals(PlaybackAutoContext.PowerState.NORMAL,
                PlaybackSystemConditionPolicy.powerState(false));
        assertEquals(PlaybackAutoContext.PowerState.POWER_SAVE,
                PlaybackSystemConditionPolicy.powerState(true));
        assertEquals(PlaybackAutoContext.PowerState.UNKNOWN,
                PlaybackSystemConditionPolicy.powerState(null));
    }

    @Test
    public void thermalMappingHonorsApiBoundaryAndSeverity() {
        assertEquals(PlaybackAutoContext.ThermalState.UNKNOWN,
                PlaybackSystemConditionPolicy.thermalState(
                        PlaybackSystemConditionPolicy.THERMAL_SEVERE, 28));
        assertEquals(PlaybackAutoContext.ThermalState.NOMINAL,
                PlaybackSystemConditionPolicy.thermalState(
                        PlaybackSystemConditionPolicy.THERMAL_NONE, 29));
        assertEquals(PlaybackAutoContext.ThermalState.NOMINAL,
                PlaybackSystemConditionPolicy.thermalState(
                        PlaybackSystemConditionPolicy.THERMAL_LIGHT, 34));
        assertEquals(PlaybackAutoContext.ThermalState.MODERATE,
                PlaybackSystemConditionPolicy.thermalState(
                        PlaybackSystemConditionPolicy.THERMAL_MODERATE, 34));
        assertEquals(PlaybackAutoContext.ThermalState.SEVERE,
                PlaybackSystemConditionPolicy.thermalState(
                        PlaybackSystemConditionPolicy.THERMAL_SEVERE, 34));
        assertEquals(PlaybackAutoContext.ThermalState.CRITICAL,
                PlaybackSystemConditionPolicy.thermalState(
                        PlaybackSystemConditionPolicy.THERMAL_CRITICAL, 34));
        assertEquals(PlaybackAutoContext.ThermalState.CRITICAL,
                PlaybackSystemConditionPolicy.thermalState(
                        PlaybackSystemConditionPolicy.THERMAL_EMERGENCY, 34));
        assertEquals(PlaybackAutoContext.ThermalState.CRITICAL,
                PlaybackSystemConditionPolicy.thermalState(
                        PlaybackSystemConditionPolicy.THERMAL_SHUTDOWN, 34));
        assertEquals(PlaybackAutoContext.ThermalState.UNKNOWN,
                PlaybackSystemConditionPolicy.thermalState(99, 34));
    }

    private static PlaybackAutoContext.NetworkSnapshot snapshot(
            Boolean available, Boolean validated, Boolean metered, Boolean roaming) {
        return new PlaybackAutoContext.NetworkSnapshot(
                available,
                validated,
                metered,
                roaming,
                PlaybackAutoContext.NetworkTransport.CELLULAR,
                PlaybackAutoContext.DataSaverState.DISABLED);
    }
}
