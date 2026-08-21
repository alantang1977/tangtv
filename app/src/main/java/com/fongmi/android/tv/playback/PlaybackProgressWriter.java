package com.fongmi.android.tv.playback;

import android.text.TextUtils;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.PlaybackDeleteTombstone;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.db.dao.HistoryDao;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.setting.Setting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PlaybackProgressWriter {

    private PlaybackProgressWriter() {
    }

    public static PlaybackProgressApplyResult applyFromLocalApi(PlaybackProgressInput input) {
        if (!ViewingRecordSyncStore.isEnabled()) return PlaybackProgressApplyResult.failed(input, "观影记录同步未开启");
        if (!ViewingRecordSyncStore.isLocalWriteEnabled()) return PlaybackProgressApplyResult.failed(input, "本机 API 修改未开启");
        return applyInternal(input, PlaybackDeleteTombstoneStore.snapshot(), false);
    }

    public static PlaybackProgressBatchResult applyFromLocalApi(List<PlaybackProgressInput> inputs) {
        PlaybackProgressBatchResult batch = new PlaybackProgressBatchResult();
        if (!ViewingRecordSyncStore.isEnabled()) {
            batch.add(PlaybackProgressApplyResult.failed((PlaybackProgressInput) null, "观影记录同步未开启"));
            return batch;
        }
        if (!ViewingRecordSyncStore.isLocalWriteEnabled()) {
            batch.add(PlaybackProgressApplyResult.failed((PlaybackProgressInput) null, "本机 API 修改未开启"));
            return batch;
        }
        return applyInternal(inputs, PlaybackDeleteTombstoneStore.snapshot(), false);
    }

    public static PlaybackProgressBatchResult deleteFromLocalApi(List<PlaybackProgressDeleteInput> inputs) {
        PlaybackProgressBatchResult batch = new PlaybackProgressBatchResult();
        if (!ViewingRecordSyncStore.isEnabled()) {
            batch.add(PlaybackProgressApplyResult.failed((PlaybackProgressDeleteInput) null, "观影记录同步未开启"));
            return batch;
        }
        if (!ViewingRecordSyncStore.isLocalWriteEnabled()) {
            batch.add(PlaybackProgressApplyResult.failed((PlaybackProgressDeleteInput) null, "本机 API 修改未开启"));
            return batch;
        }
        if (inputs == null || inputs.isEmpty()) return batch;
        for (PlaybackProgressDeleteInput input : inputs) batch.add(deleteInternal(input, null, false, false));
        return batch;
    }

    /** Called by the history UI for a deliberate user deletion. */
    public static PlaybackProgressApplyResult deleteFromUser(History history) {
        if (history == null) return PlaybackProgressApplyResult.failed((PlaybackProgressDeleteInput) null, "记录不存在");
        PlaybackProgressDeleteInput input = new PlaybackProgressDeleteInput();
        input.cid = history.getCid();
        input.historyKey = history.getKey();
        input.siteKey = history.getSiteKey();
        input.vodId = history.getVodId();
        input.episodeName = history.getVodRemarks();
        input.deletedAt = System.currentTimeMillis();
        return deleteInternal(input, null, false, true);
    }

    /** Called by the history UI for a deliberate clear-all operation. */
    public static PlaybackProgressApplyResult deleteAllFromUser(int cid) {
        PlaybackProgressDeleteInput input = new PlaybackProgressDeleteInput();
        input.cid = cid;
        input.scope = "all";
        input.confirm = true;
        input.deletedAt = System.currentTimeMillis();
        return deleteInternal(input, null, false, true);
    }

    public static PlaybackProgressBatchResult applyFromRemoteSync(List<PlaybackProgressInput> inputs, RemoteSyncConfig config) {
        PlaybackProgressBatchResult batch = new PlaybackProgressBatchResult();
        if (!ViewingRecordSyncStore.isEnabled()) {
            batch.add(PlaybackProgressApplyResult.failed((PlaybackProgressInput) null, "观影记录同步未开启"));
            return batch;
        }
        List<PlaybackDeleteTombstone> tombstones = PlaybackDeleteTombstoneStore.snapshot();
        if (inputs != null) for (PlaybackProgressInput input : inputs) {
            input = input == null ? null : input.normalize();
            if (input == null) {
                batch.add(PlaybackProgressApplyResult.failed((PlaybackProgressInput) null, "请求体不能为空"));
            } else if (config != null && !config.matchesSite(input.siteKey)) {
                batch.add(PlaybackProgressApplyResult.skipped(input, input.targetHistoryKey(targetCid(input)), "站点不匹配", 0));
            } else if (!TextUtils.isEmpty(input.configKey) && targetCid(input) <= 0) {
                batch.add(PlaybackProgressApplyResult.skipped(input, input.historyKey, "接口不匹配", 0));
            } else {
                batch.add(applyInternal(input, tombstones, true));
            }
        }
        return batch;
    }

    /** Applies delete operations from a delete-aware remote response. */
    public static PlaybackProgressBatchResult deleteFromRemoteSync(List<PlaybackProgressDeleteInput> inputs, RemoteSyncConfig config) {
        PlaybackProgressBatchResult batch = new PlaybackProgressBatchResult();
        if (!ViewingRecordSyncStore.isEnabled()) {
            batch.add(PlaybackProgressApplyResult.failed((PlaybackProgressDeleteInput) null, "观影记录同步未开启"));
            return batch;
        }
        if (inputs != null) for (PlaybackProgressDeleteInput input : inputs) {
            batch.add(deleteInternal(input, config, true, false));
        }
        return batch;
    }

    private static PlaybackProgressBatchResult applyInternal(List<PlaybackProgressInput> inputs,
                                                              List<PlaybackDeleteTombstone> tombstones,
                                                              boolean remote) {
        PlaybackProgressBatchResult batch = new PlaybackProgressBatchResult();
        if (inputs == null || inputs.isEmpty()) return batch;
        for (PlaybackProgressInput input : inputs) batch.add(applyInternal(input, tombstones, remote));
        return batch;
    }

    private static synchronized PlaybackProgressApplyResult applyInternal(PlaybackProgressInput input,
                                                                           List<PlaybackDeleteTombstone> tombstones,
                                                                           boolean remote) {
        if (Setting.isIncognito()) return PlaybackProgressApplyResult.failed(input, "隐身模式不允许写入");
        if (input == null) return PlaybackProgressApplyResult.failed((PlaybackProgressInput) null, "请求体不能为空");
        input.normalize();
        int cid = targetCid(input);
        if (cid <= 0) return PlaybackProgressApplyResult.skipped(input, input.historyKey, "接口不匹配", 0);
        long remoteUpdatedAt = input.updatedAt;
        long deletedAt = PlaybackDeleteTombstoneStore.latest(tombstones, input.configKey, cid,
                input.historyKey, input.siteKey, input.vodId);
        if (remote) {
            // The caller's snapshot may have been taken just before a local delete.
            // Re-read while holding the writer lock so an in-flight tombstone wins.
            deletedAt = Math.max(deletedAt, PlaybackDeleteTombstoneStore.latest(
                    PlaybackDeleteTombstoneStore.snapshot(), input.configKey, cid,
                    input.historyKey, input.siteKey, input.vodId));
        }
        if (remote && remoteUpdatedAt <= 0 && deletedAt > 0) {
            return PlaybackProgressApplyResult.skipped(input, input.historyKey, "记录已删除且远端缺少更新时间", deletedAt);
        }
        String error = input.validate(!remote || deletedAt <= 0);
        if (!TextUtils.isEmpty(error)) return PlaybackProgressApplyResult.failed(input, error);
        if (input.updatedAt <= 0) input.updatedAt = System.currentTimeMillis();
        if (deletedAt > 0 && input.updatedAt <= deletedAt) {
            return PlaybackProgressApplyResult.skipped(input, input.historyKey, "记录已被删除", deletedAt);
        }
        String requestedKey = input.targetHistoryKey(cid);
        History local = findLocal(cid, input, requestedKey);
        if (local != null && input.updatedAt <= local.getCreateTime()) {
            return PlaybackProgressApplyResult.skipped(input, local.getKey(), "远端记录不新于本地", local.getCreateTime());
        }
        // Keep an existing local key when matching a legacy/base history key. This
        // avoids creating a duplicate `site@@@vod@@@cid` row solely because the
        // remote payload carries a portable cid-qualified key.
        String key = local == null ? requestedKey : local.getKey();
        History history = local == null ? new History() : local.copy();
        history.setKey(key);
        history.setCid(cid);
        history.setVodName(input.vodName);
        history.setVodPic(input.vodPic);
        history.setVodFlag(input.flag);
        history.setVodRemarks(input.episodeName);
        history.setEpisodeUrl(input.episodeUrl);
        history.setPosition(input.positionMs);
        history.setDuration(input.durationMs);
        history.setSpeed(input.speed <= 0 ? 1f : input.speed);
        history.setCreateTime(input.updatedAt);
        AppDatabase.get().getHistoryDao().insertOrUpdate(history);
        RefreshEvent.history();
        return local == null ? PlaybackProgressApplyResult.created(input, history.getKey()) : PlaybackProgressApplyResult.updated(input, history.getKey());
    }

    private static synchronized PlaybackProgressApplyResult deleteInternal(PlaybackProgressDeleteInput input,
                                                                            RemoteSyncConfig filter,
                                                                            boolean remote,
                                                                            boolean notify) {
        if (Setting.isIncognito() && !notify) return PlaybackProgressApplyResult.failed(input, "隐身模式不允许清理");
        if (input == null) return PlaybackProgressApplyResult.failed((PlaybackProgressDeleteInput) null, "请求体不能为空");
        input.normalize();
        if (!matchesFilter(input, filter)) return PlaybackProgressApplyResult.skipped(input, input.historyKey, "站点不匹配");
        int cid = targetCid(input);
        if (cid <= 0) return PlaybackProgressApplyResult.skipped(input, input.historyKey, "接口不匹配");
        if (remote && input.deletedAt <= 0) return PlaybackProgressApplyResult.failed(input, "远端删除记录缺少deletedAt");
        if (!remote && input.isAllScope() && !input.confirm) return PlaybackProgressApplyResult.failed(input, "全量清理需要confirm=true");
        if (input.isSiteScope() && TextUtils.isEmpty(input.siteKey)) return PlaybackProgressApplyResult.failed(input, "按站点清理需要siteKey");
        if (!input.isAllScope() && !input.isSiteScope() && TextUtils.isEmpty(input.historyKey)
                && (TextUtils.isEmpty(input.siteKey) || TextUtils.isEmpty(input.vodId))) {
            return PlaybackProgressApplyResult.failed(input, "historyKey、siteKey+vodId或siteKey不能为空");
        }
        if (input.deletedAt <= 0) input.deletedAt = System.currentTimeMillis();

        // Persist the marker before touching History. If a playback write races this
        // deletion, the marker makes the stale write lose deterministically.
        PlaybackDeleteTombstoneStore.record(input, cid, filter);
        List<PlaybackDeleteTombstone> tombstones = PlaybackDeleteTombstoneStore.snapshot();
        HistoryDao dao = AppDatabase.get().getHistoryDao();
        List<History> candidates = candidates(dao, cid, input);
        int affected = 0;
        int newer = 0;
        long deletedAt = input.deletedAt;
        for (History history : candidates) {
            if (filter != null && !filter.matchesSite(history.getSiteKey())) continue;
            long cutoff = Math.max(deletedAt, PlaybackDeleteTombstoneStore.latest(tombstones, input.configKey, cid,
                    history.getKey(), history.getSiteKey(), history.getVodId()));
            if (remote && history.getCreateTime() > cutoff) {
                newer++;
                continue;
            }
            int count = dao.delete(cid, history.getKey());
            if (count <= 0) continue;
            AppDatabase.get().getTrackDao().delete(history.getKey());
            affected += count;
        }

        // The history screens update their adapters themselves; avoid a second refresh
        // event while a user deletion is being animated. API/remote callers still need
        // to notify other history consumers.
        if (affected > 0 && !notify) RefreshEvent.history();
        if (notify) PlaybackEventCollector.get().onHistoryDeleted(input, cid);
        if (affected > 0) return PlaybackProgressApplyResult.deleted(input, resultKey(input), affected);
        if (newer > 0) return PlaybackProgressApplyResult.skipped(input, resultKey(input), "本地记录更新于删除事件");
        return PlaybackProgressApplyResult.skipped(input, resultKey(input), "本地记录不存在");
    }

    private static List<History> candidates(HistoryDao dao, int cid, PlaybackProgressDeleteInput input) {
        Map<String, History> result = new LinkedHashMap<>();
        if (!TextUtils.isEmpty(input.historyKey)) {
            History exact = dao.find(cid, input.historyKey);
            if (exact != null) result.put(exact.getKey(), exact);
            // A history key from another device normally contains a different cid suffix.
            if (result.isEmpty() && !TextUtils.isEmpty(input.siteKey) && !TextUtils.isEmpty(input.vodId)) addByItem(dao, cid, input, result);
        } else if (!TextUtils.isEmpty(input.siteKey) && !TextUtils.isEmpty(input.vodId)) {
            addByItem(dao, cid, input, result);
        } else {
            for (History history : dao.findAll(cid)) result.put(history.getKey(), history);
        }
        if (input.isSiteScope() && !TextUtils.isEmpty(input.siteKey)) {
            result.entrySet().removeIf(entry -> !TextUtils.equals(RemoteSyncConfig.normalize(entry.getValue().getSiteKey()), RemoteSyncConfig.normalize(input.siteKey)));
        }
        if (!input.isAllScope() && !input.isSiteScope() && TextUtils.isEmpty(input.historyKey)
                && (TextUtils.isEmpty(input.siteKey) || TextUtils.isEmpty(input.vodId))) result.clear();
        return new ArrayList<>(result.values());
    }

    private static void addByItem(HistoryDao dao, int cid, PlaybackProgressDeleteInput input, Map<String, History> result) {
        String baseKey = input.siteKey + AppDatabase.SYMBOL + input.vodId;
        History base = dao.find(cid, baseKey);
        if (base != null) result.put(base.getKey(), base);
        for (History item : dao.findByKeyPrefix(cid, baseKey + AppDatabase.SYMBOL)) result.put(item.getKey(), item);
    }

    private static boolean matchesFilter(PlaybackProgressDeleteInput input, RemoteSyncConfig filter) {
        if (filter == null || filter.siteKeys == null || filter.siteKeys.isEmpty()) return true;
        if (input.isAllScope()) return true;
        return !TextUtils.isEmpty(input.siteKey) && filter.matchesSite(input.siteKey);
    }

    private static String resultKey(PlaybackProgressDeleteInput input) {
        if (!TextUtils.isEmpty(input.historyKey)) return input.historyKey;
        if (!TextUtils.isEmpty(input.siteKey) && !TextUtils.isEmpty(input.vodId)) return input.siteKey + AppDatabase.SYMBOL + input.vodId;
        return "";
    }

    private static History findLocal(int cid, PlaybackProgressInput input, String key) {
        History exact = AppDatabase.get().getHistoryDao().find(cid, key);
        if (exact != null) return exact;
        String baseKey = input.siteKey + AppDatabase.SYMBOL + input.vodId;
        History base = AppDatabase.get().getHistoryDao().find(cid, baseKey);
        if (base != null) return base;
        List<History> items = AppDatabase.get().getHistoryDao().findByKeyPrefix(cid, baseKey + AppDatabase.SYMBOL);
        if (items.isEmpty()) return null;
        return bestEpisodeMatch(items, input);
    }

    private static History bestEpisodeMatch(List<History> items, PlaybackProgressInput input) {
        for (History item : items) if (!TextUtils.isEmpty(input.episodeUrl) && TextUtils.equals(input.episodeUrl, item.getEpisodeUrl())) return item;
        for (History item : items) if (!TextUtils.isEmpty(input.flag) && TextUtils.equals(input.flag, item.getVodFlag()) && TextUtils.equals(input.episodeName, item.getVodRemarks())) return item;
        for (History item : items) if (TextUtils.equals(input.episodeName, item.getVodRemarks())) return item;
        return items.get(0);
    }

    private static int targetCid(PlaybackProgressInput input) {
        int cid = PlaybackConfigIdentity.cidForKey(input.configKey);
        if (cid > 0) return cid;
        if (!TextUtils.isEmpty(input.configKey)) return 0;
        return input.cid > 0 ? input.cid : VodConfig.getCid();
    }

    private static int targetCid(PlaybackProgressDeleteInput input) {
        int cid = PlaybackConfigIdentity.cidForKey(input.configKey);
        if (cid > 0) return cid;
        if (!TextUtils.isEmpty(input.configKey)) return 0;
        if (input.cid > 0) return input.cid;
        try {
            int index = input.historyKey.lastIndexOf(AppDatabase.SYMBOL);
            if (index >= 0) return Integer.parseInt(input.historyKey.substring(index + AppDatabase.SYMBOL.length()));
        } catch (Exception ignored) {
        }
        return VodConfig.getCid();
    }
}
