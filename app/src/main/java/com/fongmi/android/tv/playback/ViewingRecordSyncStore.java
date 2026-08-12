package com.fongmi.android.tv.playback;

import android.content.Context;

import com.fongmi.android.tv.R;
import com.github.catvod.utils.Prefers;

public final class ViewingRecordSyncStore {

    private static final String KEY_ENABLED = "viewing_record_sync_enabled";
    private static final String KEY_LOCAL_WRITE = "viewing_record_sync_local_write";

    private ViewingRecordSyncStore() {
    }

    public static boolean isEnabled() {
        return Prefers.getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(boolean enabled) {
        Prefers.put(KEY_ENABLED, enabled);
    }

    public static boolean isLocalWriteEnabled() {
        return Prefers.getBoolean(KEY_LOCAL_WRITE, false);
    }

    public static void setLocalWriteEnabled(boolean enabled) {
        Prefers.put(KEY_LOCAL_WRITE, enabled);
    }

    public static String summary(Context context) {
        String state = context.getString(isEnabled() ? R.string.setting_enable : R.string.setting_disable);
        return context.getString(R.string.viewing_record_sync_entry_summary,
                state,
                PlaybackRemoteSyncStore.activeCount(),
                PlaybackRemoteSyncStore.totalCount(),
                PlaybackWebhookStore.activeCount(),
                PlaybackWebhookStore.totalCount());
    }
}
