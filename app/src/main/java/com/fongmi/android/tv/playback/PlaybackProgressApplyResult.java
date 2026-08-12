package com.fongmi.android.tv.playback;

public class PlaybackProgressApplyResult {

    public boolean success;
    public String action;
    public String message;
    public String historyKey;
    public String siteKey;
    public String configKey;
    public String vodId;
    public String episodeName;
    public int affected;
    public long localUpdatedAt;
    public long remoteUpdatedAt;

    public static PlaybackProgressApplyResult created(PlaybackProgressInput input, String historyKey) {
        return success(input, historyKey, "created");
    }

    public static PlaybackProgressApplyResult updated(PlaybackProgressInput input, String historyKey) {
        return success(input, historyKey, "updated");
    }

    public static PlaybackProgressApplyResult skipped(PlaybackProgressInput input, String historyKey, String message, long localUpdatedAt) {
        PlaybackProgressApplyResult result = base(input, historyKey);
        result.success = true;
        result.action = "skipped";
        result.message = message == null ? "" : message;
        result.localUpdatedAt = localUpdatedAt;
        return result;
    }

    public static PlaybackProgressApplyResult skipped(PlaybackProgressDeleteInput input, String historyKey, String message) {
        PlaybackProgressApplyResult result = base(input, historyKey);
        result.success = true;
        result.action = "skipped";
        result.message = message == null ? "" : message;
        return result;
    }

    public static PlaybackProgressApplyResult deleted(PlaybackProgressDeleteInput input, String historyKey, int affected) {
        PlaybackProgressApplyResult result = base(input, historyKey);
        result.success = true;
        result.action = "deleted";
        result.message = "";
        result.affected = affected;
        return result;
    }

    public static PlaybackProgressApplyResult failed(PlaybackProgressInput input, String message) {
        PlaybackProgressApplyResult result = base(input, input == null ? "" : input.historyKey);
        result.success = false;
        result.action = "failed";
        result.message = message == null ? "" : message;
        return result;
    }

    public static PlaybackProgressApplyResult failed(PlaybackProgressDeleteInput input, String message) {
        PlaybackProgressApplyResult result = base(input, input == null ? "" : input.historyKey);
        result.success = false;
        result.action = "failed";
        result.message = message == null ? "" : message;
        return result;
    }

    private static PlaybackProgressApplyResult success(PlaybackProgressInput input, String historyKey, String action) {
        PlaybackProgressApplyResult result = base(input, historyKey);
        result.success = true;
        result.action = action;
        result.message = "";
        result.affected = 1;
        return result;
    }

    private static PlaybackProgressApplyResult base(PlaybackProgressInput input, String historyKey) {
        PlaybackProgressApplyResult result = new PlaybackProgressApplyResult();
        result.historyKey = historyKey == null ? "" : historyKey;
        if (input != null) {
            result.siteKey = input.siteKey;
            result.configKey = input.configKey;
            result.vodId = input.vodId;
            result.episodeName = input.episodeName;
            result.remoteUpdatedAt = input.updatedAt;
        }
        return result;
    }

    private static PlaybackProgressApplyResult base(PlaybackProgressDeleteInput input, String historyKey) {
        PlaybackProgressApplyResult result = new PlaybackProgressApplyResult();
        result.historyKey = historyKey == null ? "" : historyKey;
        if (input != null) {
            result.siteKey = input.siteKey;
            result.configKey = input.configKey;
            result.vodId = input.vodId;
            result.episodeName = input.episodeName;
            result.remoteUpdatedAt = input.deletedAt;
        }
        return result;
    }
}
