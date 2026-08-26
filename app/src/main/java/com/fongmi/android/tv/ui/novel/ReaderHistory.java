package com.fongmi.android.tv.ui.novel;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Task;

import java.util.Objects;

/**
 * 小说 / 漫画阅读进度记录（对齐 {@link com.fongmi.android.tv.ui.audio.AudioHistory}）。
 *
 * 复用 History 表，因此阅读记录会和影视记录一起出现在历史列表里：
 * - episodeUrl / vodRemarks：上次读到的章节 URL 与章节名，用于重进时定位章节
 * - position / duration：章节内锚点序号与锚点总数。锚点即小说的段落、漫画的页，
 *   position/duration 恰好等于「已读比例」，历史列表的进度条无需特殊处理。
 *
 * 记锚点序号而不记滚动百分比，是因为文档高度并不稳定：漫画图片分批懒加载且异步解码，
 * 小说段落用了 content-visibility:auto（屏外段落先按估算高度占位），
 * 同一像素位置在不同时刻对应的内容不同。锚点序号还能在改字号 / 行高后仍回到同一段文字。
 *
 * 兼容：早期版本的小说记录存的是「百分比 × {@link #SCALE}」且 duration = SCALE，
 * 读取方无法区分，因此仍按 position/duration 得到比例交给 HTML，由 HTML 兜底处理。
 */
public final class ReaderHistory {

    /** 旧版小说进度的放大倍数（现仅用于兼容历史记录）。 */
    public static final long SCALE = 10000L;

    private ReaderHistory() {
    }

    /** 阅读记录 key，与音频一致带上 cid，避免换配置后串记录。 */
    public static String buildKey(String siteKey, String vodId) {
        return siteKey + AppDatabase.SYMBOL + vodId + AppDatabase.SYMBOL + VodConfig.getCid();
    }

    private static String buildLegacyKey(String siteKey, String vodId) {
        return siteKey + AppDatabase.SYMBOL + vodId;
    }

    public static boolean canUse(String siteKey, String vodId) {
        return !TextUtils.isEmpty(siteKey) && !TextUtils.isEmpty(vodId);
    }

    /** 查询已保存的阅读记录；null 表示没读过。 */
    @Nullable
    public static History find(String siteKey, String vodId) {
        if (!canUse(siteKey, vodId)) return null;
        History history = History.find(buildKey(siteKey, vodId));
        if (history == null) history = History.find(buildLegacyKey(siteKey, vodId));
        return history;
    }

    /**
     * 保存阅读进度（异步）。
     *
     * @param anchor 章节内锚点序号（0 基）：小说=段落，漫画/PDF=页
     * @param total  锚点总数；<= 0 时视为无效位置，不保存
     */
    public static void save(Record record, String chapterName, String chapterUrl, int anchor, int total) {
        if (Setting.isIncognito() || record == null || !record.canUse()) return;
        if (total <= 0) return;
        long duration = total;
        long position = Math.max(0, Math.min(duration, anchor));
        Task.execute(() -> saveSync(record, chapterName, chapterUrl, position, duration));
    }

    private static void saveSync(Record record, String chapterName, String chapterUrl, long position, long duration) {
        String key = buildKey(record.siteKey, record.vodId);
        History history = find(record.siteKey, record.vodId);
        boolean created = history == null;
        if (created) {
            history = new History();
            history.setKey(key);
        } else if (!key.equals(history.getKey())) {
            history.replace(key);
            created = true;
        }
        boolean changedChapter = !TextUtils.equals(chapterUrl, history.getEpisodeUrl());
        history.setCid(VodConfig.getCid());
        history.setVodName(record.vodName);
        history.setVodPic(record.vodPic);
        history.setVodFlag(record.vodFlag);
        history.setVodRemarks(chapterName);
        history.setEpisodeUrl(chapterUrl);
        history.setPosition(position);
        history.setDuration(duration);
        history.setCreateTime(System.currentTimeMillis());
        history.save();
        if (created || changedChapter) App.post(RefreshEvent::history);
    }

    /** 阅读身份（一本书 / 一部漫画）。 */
    public static final class Record {

        private final String siteKey;
        private final String vodId;
        private final String vodFlag;
        private final String vodName;
        private final String vodPic;

        public Record(String siteKey, String vodId, String vodFlag, String vodName, String vodPic) {
            this.siteKey = Objects.toString(siteKey, "");
            this.vodId = Objects.toString(vodId, "");
            this.vodFlag = Objects.toString(vodFlag, "");
            this.vodName = Objects.toString(vodName, "");
            this.vodPic = Objects.toString(vodPic, "");
        }

        public boolean canUse() {
            return ReaderHistory.canUse(siteKey, vodId);
        }
    }
}
