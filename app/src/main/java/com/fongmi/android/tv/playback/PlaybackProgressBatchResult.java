package com.fongmi.android.tv.playback;

import java.util.ArrayList;
import java.util.List;

public class PlaybackProgressBatchResult {

    public int total;
    public int applied;
    public int created;
    public int updated;
    public int deleted;
    public int skipped;
    public int failed;
    public final List<PlaybackProgressApplyResult> items = new ArrayList<>();

    public void add(PlaybackProgressApplyResult result) {
        items.add(result);
        total++;
        if (result == null || !result.success || "failed".equals(result.action)) {
            failed++;
        } else if ("skipped".equals(result.action)) {
            skipped++;
        } else {
            applied += result.affected > 0 ? result.affected : 1;
            if ("created".equals(result.action)) created++;
            if ("updated".equals(result.action)) updated++;
            if ("deleted".equals(result.action)) deleted += result.affected;
        }
    }

    public void addAll(PlaybackProgressBatchResult batch) {
        if (batch == null || batch.items == null) return;
        for (PlaybackProgressApplyResult item : batch.items) add(item);
    }
}
