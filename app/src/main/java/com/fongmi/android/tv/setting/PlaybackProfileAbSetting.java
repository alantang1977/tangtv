package com.fongmi.android.tv.setting;

import android.content.SharedPreferences;

import com.fongmi.android.tv.player.PlaybackProfileAbAcceptancePolicy;
import com.fongmi.android.tv.player.PlaybackProfileAbIdentity;
import com.fongmi.android.tv.player.PlaybackProfileAbPolicy;
import com.fongmi.android.tv.player.PlaybackProfileAbStore;
import com.github.catvod.utils.Prefers;

import java.util.Map;

/** Device-local enrollment and sample storage for automatic/profile A/B validation. */
public final class PlaybackProfileAbSetting {

    public static final String KEY_SCHEMA =
            "playback_experiment_profile_ab_schema";
    public static final String KEY_DEVICE =
            "playback_experiment_profile_ab_device";
    public static final String KEY_ENABLED =
            "playback_experiment_profile_ab_enabled";
    public static final String KEY_SAMPLES =
            "playback_experiment_profile_ab_samples_v1";

    private PlaybackProfileAbSetting() {
    }

    public static PlaybackProfileAbPolicy.EnrollmentResolution
    getEnrollmentResolution() {
        Map<String, ?> values;
        try {
            values = Prefers.getPrefers().getAll();
        } catch (Throwable ignored) {
            values = Map.of();
        }
        return PlaybackProfileAbPolicy.resolveEnrollment(
                new PlaybackProfileAbPolicy.RawEnrollment(
                        values.get(KEY_SCHEMA),
                        values.get(KEY_DEVICE),
                        values.get(KEY_ENABLED)),
                PlaybackProfileAbIdentity.currentDeviceDigest());
    }

    public static boolean isEnrolled() {
        return getEnrollmentResolution().active();
    }

    public static boolean putEnrolled(boolean enabled) {
        PlaybackProfileAbPolicy.EnrollmentResolution current =
                getEnrollmentResolution();
        if (current.status()
                == PlaybackProfileAbPolicy.EnrollmentStatus.FUTURE_SCHEMA) {
            return false;
        }
        try {
            SharedPreferences.Editor editor = Prefers.getPrefers().edit();
            if (!enabled) {
                editor.remove(KEY_SCHEMA);
                editor.remove(KEY_DEVICE);
                editor.remove(KEY_ENABLED);
            } else {
                String deviceDigest =
                        PlaybackProfileAbIdentity.currentDeviceDigest();
                if (!PlaybackProfileAbIdentity.validDigest(deviceDigest)) {
                    return false;
                }
                editor.putInt(
                        KEY_SCHEMA,
                        PlaybackProfileAbPolicy.CURRENT_SCHEMA_VERSION);
                editor.putString(KEY_DEVICE, deviceDigest);
                editor.putBoolean(KEY_ENABLED, true);
            }
            editor.apply();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static PlaybackProfileAbStore store() {
        return Holder.STORE;
    }

    public static PlaybackProfileAbAcceptancePolicy.Report report(
            long nowEpochMs) {
        return PlaybackProfileAbAcceptancePolicy.evaluate(
                store().snapshotForDevice(
                        nowEpochMs,
                        PlaybackProfileAbIdentity.currentDeviceDigest()));
    }

    public static void clearReport() {
        store().clear();
    }

    private static final class Holder {

        private static final PlaybackProfileAbStore STORE =
                new PlaybackProfileAbStore(new PreferencesBackend());
    }

    private static final class PreferencesBackend
            implements PlaybackProfileAbStore.Backend {

        @Override
        public String read() {
            return Prefers.getString(KEY_SAMPLES);
        }

        @Override
        public void write(String value) {
            Prefers.put(KEY_SAMPLES, value);
        }

        @Override
        public void clear() {
            Prefers.remove(KEY_SAMPLES);
        }
    }
}
