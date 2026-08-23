package com.fongmi.android.tv.utils;

import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.player.PlaybackSpeedMeter;
import com.fongmi.android.tv.player.PlayerManager;

public class Traffic {

    private static final PlaybackSpeedMeter meter = new PlaybackSpeedMeter();

    /**
     * Renders the current download speed, preferring the playback kernel's own byte
     * accounting so the readout still works on ROMs whose per-UID TrafficStats
     * counter is missing. Pass the active player, or null when none is attached.
     */
    public static void setSpeed(TextView view, @Nullable PlayerManager player) {
        meter.sample(player);
        String text = meter.getText();
        view.setText(text);
        view.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }

    public static void reset() {
        meter.reset();
    }
}
