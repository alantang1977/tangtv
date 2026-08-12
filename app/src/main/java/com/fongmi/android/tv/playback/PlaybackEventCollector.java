package com.fongmi.android.tv.playback;

import androidx.annotation.Nullable;
import androidx.media3.common.Player;

import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.setting.Setting;

public final class PlaybackEventCollector {

    public static final String START = "playback.start";
    public static final String PROGRESS = "playback.progress";
    public static final String PAUSE = "playback.pause";
    public static final String RESUME = "playback.resume";
    public static final String STOP = "playback.stop";
    public static final String ENDED = "playback.ended";

    private static final PlaybackEventCollector INSTANCE = new PlaybackEventCollector();

    private final PlaybackWebhookSender sender;
    private String activeSignature;
    private boolean started;
    private boolean lastPlaying;

    private PlaybackEventCollector() {
        this.sender = new PlaybackWebhookSender();
    }

    public static PlaybackEventCollector get() {
        return INSTANCE;
    }

    public void setPlayer(@Nullable PlayerManager player) {
        PlaybackRuntime.setPlayer(player);
    }

    public void updateHistory(@Nullable History history) {
        PlaybackRuntime.updateHistory(history);
    }

    public synchronized void onProgress(@Nullable History history, @Nullable PlayerManager player) {
        updateHistory(history);
        PlaybackRecord record = snapshot(history, player, PROGRESS);
        if (record == null || player == null || !player.isPlaying()) return;
        if (!started) startIfNeeded(record, history);
        sender.scheduleProgress(record);
    }

    public synchronized void onPlaybackStateChanged(@Nullable PlayerManager player, int state) {
        History history = current(player);
        PlaybackRecord record = snapshot(history, player, "");
        if (record == null) return;
        if (state == Player.STATE_READY && player != null && player.isPlaying()) {
            startIfNeeded(record, history);
            sender.scheduleProgress(record.withEvent(PROGRESS));
        } else if (state == Player.STATE_ENDED) {
            if (started) sender.sendFinalThen(record, ENDED);
            resetStarted();
        }
    }

    public synchronized void onIsPlayingChanged(@Nullable PlayerManager player, boolean isPlaying) {
        History history = current(player);
        PlaybackRecord record = snapshot(history, player, "");
        if (record == null) return;
        if (isPlaying) {
            if (!started) startIfNeeded(record, history);
            else if (!lastPlaying) sender.sendImmediate(record.withEvent(RESUME));
            sender.scheduleProgress(record.withEvent(PROGRESS));
        } else if (started && lastPlaying && player != null && player.getPlaybackState() == Player.STATE_READY) {
            sender.sendFinalThen(record, PAUSE);
        }
        lastPlaying = isPlaying;
    }

    public synchronized void onStop(@Nullable PlayerManager player) {
        History history = current(player);
        PlaybackRecord record = snapshot(history, player, "");
        if (record != null && started) sender.sendFinalThen(record, STOP);
        resetStarted();
    }

    public void onHistoryDeleted(PlaybackProgressDeleteInput input, int cid) {
        if (input == null) return;
        sender.sendImmediate(PlaybackRecord.deleted(input, cid));
    }

    private void startIfNeeded(PlaybackRecord record, History history) {
        String signature = signature(history);
        if (!signature.equals(activeSignature)) {
            activeSignature = signature;
            started = false;
            lastPlaying = false;
            sender.cancelAllProgress();
        }
        if (started) return;
        sender.sendImmediate(record.withEvent(START));
        started = true;
        lastPlaying = true;
    }

    private PlaybackRecord snapshot(@Nullable History history, @Nullable PlayerManager player, String event) {
        if (Setting.isIncognito() || !PlaybackRecord.canCreate(history)) return null;
        String sessionId = PlaybackRuntime.ensureSession(history);
        return PlaybackRecord.from(history, player, event, sessionId);
    }

    private History current(@Nullable PlayerManager player) {
        String key = null;
        try {
            key = player == null || player.isReleased() ? null : player.getKey();
        } catch (Exception ignored) {
        }
        return PlaybackRuntime.currentHistoryFor(key);
    }

    private void resetStarted() {
        started = false;
        lastPlaying = false;
        activeSignature = "";
        sender.cancelAllProgress();
        PlaybackRuntime.clearSession();
    }

    private static String signature(History history) {
        if (history == null) return "";
        return history.getKey() + '\n' + history.getVodFlag() + '\n' + history.getVodRemarks();
    }
}
