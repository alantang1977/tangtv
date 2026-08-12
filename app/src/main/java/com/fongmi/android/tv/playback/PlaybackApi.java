package com.fongmi.android.tv.playback;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.setting.Setting;

public final class PlaybackApi {

    private PlaybackApi() {
    }

    @Nullable
    public static PlaybackRecord current(String callerSiteKey) {
        if (TextUtils.isEmpty(callerSiteKey) || Setting.isIncognito()) return null;
        History history = PlaybackRuntime.currentHistory();
        if (!PlaybackRecord.canCreate(history)) return null;
        if (!TextUtils.equals(callerSiteKey, history.getSiteKey())) return null;
        PlayerManager player = PlaybackRuntime.playerFor(history.getKey());
        String sessionId = PlaybackRuntime.ensureSession(history);
        return PlaybackRecord.from(history, player, null, sessionId).copyFor(PlaybackFieldPolicy.apiSafe());
    }
}
