package com.fongmi.android.tv.player;

/** Pure mappings for conservative network-cost, Data Saver, power and thermal facts. */
public final class PlaybackSystemConditionPolicy {

    static final long FACT_TTL_MS = 2 * 60_000;
    static final int API_THERMAL_STATUS = 29;

    static final int DATA_SAVER_DISABLED = 1;
    static final int DATA_SAVER_WHITELISTED = 2;
    static final int DATA_SAVER_ENABLED = 3;

    static final int THERMAL_NONE = 0;
    static final int THERMAL_LIGHT = 1;
    static final int THERMAL_MODERATE = 2;
    static final int THERMAL_SEVERE = 3;
    static final int THERMAL_CRITICAL = 4;
    static final int THERMAL_EMERGENCY = 5;
    static final int THERMAL_SHUTDOWN = 6;

    private PlaybackSystemConditionPolicy() {
    }

    public static NetworkResult evaluateNetwork(PlaybackAutoContext.NetworkSnapshot snapshot) {
        if (snapshot == null || !snapshot.hasEvidence() || !Boolean.TRUE.equals(snapshot.available())) {
            return NetworkResult.unknown();
        }
        if (Boolean.TRUE.equals(snapshot.roaming())) {
            return new NetworkResult(PlaybackAutoContext.NetworkCost.ROAMING,
                    PlaybackAutoContext.Confidence.HIGH);
        }
        if (snapshot.metered() == null) return NetworkResult.unknown();
        PlaybackAutoContext.NetworkCost cost = snapshot.metered()
                ? PlaybackAutoContext.NetworkCost.METERED
                : PlaybackAutoContext.NetworkCost.UNMETERED;
        PlaybackAutoContext.Confidence confidence = Boolean.FALSE.equals(snapshot.roaming())
                && snapshot.hasCoreEvidence()
                ? PlaybackAutoContext.Confidence.HIGH
                : PlaybackAutoContext.Confidence.MEDIUM;
        return new NetworkResult(cost, confidence);
    }

    public static PlaybackAutoContext.Confidence networkSnapshotConfidence(
            PlaybackAutoContext.NetworkSnapshot snapshot) {
        if (snapshot == null || !snapshot.hasEvidence()) return PlaybackAutoContext.Confidence.UNKNOWN;
        return snapshot.hasCoreEvidence()
                ? PlaybackAutoContext.Confidence.HIGH
                : PlaybackAutoContext.Confidence.MEDIUM;
    }

    public static PlaybackAutoContext.DataSaverState dataSaverState(Integer rawStatus) {
        if (rawStatus == null) return PlaybackAutoContext.DataSaverState.UNKNOWN;
        return switch (rawStatus) {
            case DATA_SAVER_DISABLED -> PlaybackAutoContext.DataSaverState.DISABLED;
            case DATA_SAVER_WHITELISTED -> PlaybackAutoContext.DataSaverState.WHITELISTED;
            case DATA_SAVER_ENABLED -> PlaybackAutoContext.DataSaverState.ENABLED;
            default -> PlaybackAutoContext.DataSaverState.UNKNOWN;
        };
    }

    public static PlaybackAutoContext.PowerState powerState(Boolean powerSave) {
        if (powerSave == null) return PlaybackAutoContext.PowerState.UNKNOWN;
        return powerSave ? PlaybackAutoContext.PowerState.POWER_SAVE : PlaybackAutoContext.PowerState.NORMAL;
    }

    public static PlaybackAutoContext.ThermalState thermalState(Integer rawStatus, int sdkInt) {
        if (sdkInt < API_THERMAL_STATUS || rawStatus == null) return PlaybackAutoContext.ThermalState.UNKNOWN;
        return switch (rawStatus) {
            case THERMAL_NONE, THERMAL_LIGHT -> PlaybackAutoContext.ThermalState.NOMINAL;
            case THERMAL_MODERATE -> PlaybackAutoContext.ThermalState.MODERATE;
            case THERMAL_SEVERE -> PlaybackAutoContext.ThermalState.SEVERE;
            case THERMAL_CRITICAL, THERMAL_EMERGENCY, THERMAL_SHUTDOWN -> PlaybackAutoContext.ThermalState.CRITICAL;
            default -> PlaybackAutoContext.ThermalState.UNKNOWN;
        };
    }

    public record NetworkResult(
            PlaybackAutoContext.NetworkCost cost,
            PlaybackAutoContext.Confidence confidence) {

        public NetworkResult {
            cost = cost == null ? PlaybackAutoContext.NetworkCost.UNKNOWN : cost;
            confidence = confidence == null ? PlaybackAutoContext.Confidence.UNKNOWN : confidence;
        }

        public static NetworkResult unknown() {
            return new NetworkResult(PlaybackAutoContext.NetworkCost.UNKNOWN,
                    PlaybackAutoContext.Confidence.UNKNOWN);
        }

        public boolean known() {
            return cost != PlaybackAutoContext.NetworkCost.UNKNOWN
                    && confidence != PlaybackAutoContext.Confidence.UNKNOWN;
        }
    }
}
