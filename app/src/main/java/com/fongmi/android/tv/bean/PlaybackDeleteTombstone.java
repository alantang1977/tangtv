package com.fongmi.android.tv.bean;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * A durable marker for a playback record that was deleted.  The marker is
 * intentionally kept separately from History so an old remote upsert cannot
 * recreate a record after it has been removed locally.
 */
@Entity(indices = {@Index(value = "deletedAt")})
public class PlaybackDeleteTombstone {

    @NonNull
    @PrimaryKey
    public String id = "";
    @NonNull
    public String configKey = "";
    @NonNull
    public String scope = "item";
    @NonNull
    public String historyKey = "";
    @NonNull
    public String siteKey = "";
    @NonNull
    public String vodId = "";
    public long deletedAt;

    public PlaybackDeleteTombstone() {
    }
}
