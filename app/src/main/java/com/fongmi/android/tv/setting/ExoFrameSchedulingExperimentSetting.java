package com.fongmi.android.tv.setting;

import android.content.SharedPreferences;

import com.fongmi.android.tv.player.exo.ExoFrameSchedulingExperimentIdentity;
import com.fongmi.android.tv.player.exo.ExoFrameSchedulingExperimentPolicy;
import com.github.catvod.utils.Prefers;

import java.util.Map;

/** Device-local, non-backed-up assignment for the EXO frame scheduling experiment. */
public final class ExoFrameSchedulingExperimentSetting {

    public static final String KEY_SCHEMA =
            "playback_experiment_exo_frame_schema";
    public static final String KEY_DEVICE =
            "playback_experiment_exo_frame_device";
    public static final String KEY_UNIT =
            "playback_experiment_exo_frame_unit";

    private ExoFrameSchedulingExperimentSetting() {
    }

    public static ExoFrameSchedulingExperimentPolicy.AssignmentResolution
    getResolution() {
        Map<String, ?> values;
        try {
            values = Prefers.getPrefers().getAll();
        } catch (Throwable ignored) {
            values = Map.of();
        }
        return ExoFrameSchedulingExperimentPolicy.resolveAssignment(
                new ExoFrameSchedulingExperimentPolicy.RawAssignment(
                        values.get(KEY_SCHEMA),
                        values.get(KEY_DEVICE),
                        values.get(KEY_UNIT)),
                ExoFrameSchedulingExperimentIdentity.currentDeviceDigest());
    }

    public static ExoFrameSchedulingExperimentPolicy.Unit getUnit() {
        ExoFrameSchedulingExperimentPolicy.AssignmentResolution resolution =
                getResolution();
        return resolution.status()
                == ExoFrameSchedulingExperimentPolicy.Status.CURRENT
                ? resolution.assignment().unit() : null;
    }

    public static boolean putUnit(
            ExoFrameSchedulingExperimentPolicy.Unit unit) {
        ExoFrameSchedulingExperimentPolicy.AssignmentResolution current =
                getResolution();
        if (current.status()
                == ExoFrameSchedulingExperimentPolicy.Status.FUTURE_SCHEMA) {
            return false;
        }
        try {
            SharedPreferences.Editor editor = Prefers.getPrefers().edit();
            if (unit == null) {
                editor.remove(KEY_SCHEMA);
                editor.remove(KEY_DEVICE);
                editor.remove(KEY_UNIT);
            } else {
                editor.putInt(
                        KEY_SCHEMA,
                        ExoFrameSchedulingExperimentPolicy.CURRENT_SCHEMA_VERSION);
                editor.putString(
                        KEY_DEVICE,
                        ExoFrameSchedulingExperimentIdentity.currentDeviceDigest());
                editor.putString(KEY_UNIT, unit.id());
            }
            editor.apply();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
