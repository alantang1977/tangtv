package com.fongmi.android.tv.setting;

import android.content.SharedPreferences;

import com.fongmi.android.tv.player.PlaybackExperimentPolicy;
import com.github.catvod.utils.Prefers;

import java.util.Map;

/** Device-local persistence for internal experiments; production automation is always admitted. */
public final class PlaybackExperimentSetting {

    public static final String KEY_SCHEMA = "playback_experiment_schema";
    public static final String KEY_ENABLED = "playback_experiment_enabled";
    public static final String KEY_EXO = "playback_experiment_exo";
    public static final String KEY_MPV = "playback_experiment_mpv";
    public static final String KEY_IJK = "playback_experiment_ijk";

    private static volatile PlaybackExperimentPolicy.Resolution cachedResolution;

    private PlaybackExperimentSetting() {
    }

    public static PlaybackExperimentPolicy.Resolution ensureInitialized() {
        PlaybackExperimentPolicy.Resolution cached = cachedResolution;
        if (cached != null) return cached;
        synchronized (PlaybackExperimentSetting.class) {
            cached = cachedResolution;
            if (cached != null) return cached;
            PlaybackExperimentPolicy.Resolution resolution = read();
            if (resolution.writeBack()) write(resolution.state());
            cachedResolution = resolution;
            return resolution;
        }
    }

    public static PlaybackExperimentPolicy.State getState() {
        return ensureInitialized().state();
    }

    public static boolean isAllowed(PlaybackExperimentPolicy.Action action) {
        return getState().allows(action);
    }

    public static boolean isEnabled() {
        return getState().enabled();
    }

    public static boolean isDomainEnabled(PlaybackExperimentPolicy.Domain domain) {
        return getState().domainEnabled(domain);
    }

    public static synchronized void putEnabled(boolean enabled) {
        PlaybackExperimentPolicy.Resolution resolution = ensureInitialized();
        if (resolution.status()
                == PlaybackExperimentPolicy.Status.FUTURE_SCHEMA) return;
        PlaybackExperimentPolicy.State current = resolution.state();
        update(new PlaybackExperimentPolicy.State(
                PlaybackExperimentPolicy.CURRENT_SCHEMA_VERSION,
                enabled,
                current.exoEnabled(),
                current.mpvEnabled(),
                current.ijkEnabled()));
    }

    public static synchronized void putDomainEnabled(
            PlaybackExperimentPolicy.Domain domain,
            boolean enabled) {
        PlaybackExperimentPolicy.Resolution resolution = ensureInitialized();
        if (resolution.status()
                == PlaybackExperimentPolicy.Status.FUTURE_SCHEMA) return;
        PlaybackExperimentPolicy.State current = resolution.state();
        update(new PlaybackExperimentPolicy.State(
                PlaybackExperimentPolicy.CURRENT_SCHEMA_VERSION,
                current.enabled(),
                domain == PlaybackExperimentPolicy.Domain.EXO
                        ? enabled : current.exoEnabled(),
                domain == PlaybackExperimentPolicy.Domain.MPV
                        ? enabled : current.mpvEnabled(),
                domain == PlaybackExperimentPolicy.Domain.IJK
                        ? enabled : current.ijkEnabled()));
    }

    public static synchronized void rollbackToStable() {
        PlaybackExperimentPolicy.Resolution resolution = ensureInitialized();
        if (resolution.status()
                == PlaybackExperimentPolicy.Status.FUTURE_SCHEMA) return;
        PlaybackExperimentPolicy.State current = resolution.state();
        update(new PlaybackExperimentPolicy.State(
                PlaybackExperimentPolicy.CURRENT_SCHEMA_VERSION,
                false,
                current.exoEnabled(),
                current.mpvEnabled(),
                current.ijkEnabled()));
    }

    private static PlaybackExperimentPolicy.Resolution read() {
        Map<String, ?> values;
        try {
            values = Prefers.getPrefers().getAll();
        } catch (Throwable ignored) {
            values = Map.of();
        }
        return PlaybackExperimentPolicy.resolve(
                new PlaybackExperimentPolicy.RawState(
                        values.get(KEY_SCHEMA),
                        values.get(KEY_ENABLED),
                        values.get(KEY_EXO),
                        values.get(KEY_MPV),
                        values.get(KEY_IJK)));
    }

    private static void update(PlaybackExperimentPolicy.State state) {
        write(state);
        cachedResolution = new PlaybackExperimentPolicy.Resolution(
                state,
                PlaybackExperimentPolicy.Status.CURRENT,
                false,
                true);
    }

    private static void write(PlaybackExperimentPolicy.State state) {
        PlaybackExperimentPolicy.State safe = state == null
                ? PlaybackExperimentPolicy.State.stable() : state;
        SharedPreferences.Editor editor = Prefers.getPrefers().edit();
        editor.putInt(KEY_SCHEMA, PlaybackExperimentPolicy.CURRENT_SCHEMA_VERSION);
        editor.putBoolean(KEY_ENABLED, safe.enabled());
        editor.putBoolean(KEY_EXO, safe.exoEnabled());
        editor.putBoolean(KEY_MPV, safe.mpvEnabled());
        editor.putBoolean(KEY_IJK, safe.ijkEnabled());
        editor.apply();
    }
}
