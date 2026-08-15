package com.fongmi.android.tv.bean;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.db.AppDatabase;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Stores a user's explicit source-to-TMDB-season decision separately from media identity. */
public class TmdbSeasonMatchCache {

    private static final int VERSION = 1;

    private Map<String, Entry> items;

    public enum Mode {
        MANUAL_SEASON,
        MANUAL_FLAT,
        MANUAL_MULTI_SLICE
    }

    public TmdbSeasonMatchCache() {
        this.items = new HashMap<>();
    }

    public static TmdbSeasonMatchCache objectFrom(String str) {
        try {
            TmdbSeasonMatchCache cache = App.gson().fromJson(str, TmdbSeasonMatchCache.class);
            return cache == null ? new TmdbSeasonMatchCache() : cache;
        } catch (Exception e) {
            return new TmdbSeasonMatchCache();
        }
    }

    public Entry find(String siteKey, String vodId, String sourceTitle, int tmdbId) {
        if (!hasScope(siteKey, vodId, sourceTitle) || tmdbId <= 0) return null;
        Entry entry = getItems().get(key(siteKey, vodId, sourceTitle));
        return entry != null && entry.matches(tmdbId) ? entry : null;
    }

    public void put(
            String siteKey,
            String vodId,
            String sourceTitle,
            int tmdbId,
            String mediaType,
            Integer seasonNumber,
            Mode mode,
            String sourceFingerprint,
            int sourceEpisodeCount,
            int tmdbSeasonEpisodeCount) {
        if (!hasScope(siteKey, vodId, sourceTitle) || tmdbId <= 0 || !"tv".equalsIgnoreCase(mediaType)) return;
        if (mode == Mode.MANUAL_SEASON && (seasonNumber == null || seasonNumber < 0)) return;
        if ((mode == Mode.MANUAL_FLAT || mode == Mode.MANUAL_MULTI_SLICE) && seasonNumber != null) return;
        if (mode == null) return;
        Entry entry = Entry.create(tmdbId, mediaType, seasonNumber, mode, sourceFingerprint, sourceEpisodeCount, tmdbSeasonEpisodeCount);
        getItems().put(key(siteKey, vodId, sourceTitle), entry);
    }

    public void remove(String siteKey, String vodId, String sourceTitle) {
        if (!hasScope(siteKey, vodId, sourceTitle)) return;
        getItems().remove(key(siteKey, vodId, sourceTitle));
    }

    public boolean removeIfMediaChanged(String siteKey, String vodId, String sourceTitle, int tmdbId, String mediaType) {
        if (!hasScope(siteKey, vodId, sourceTitle) || tmdbId <= 0) return false;
        String key = key(siteKey, vodId, sourceTitle);
        Entry entry = getItems().get(key);
        if (entry == null || entry.matches(tmdbId) && "tv".equalsIgnoreCase(mediaType)) return false;
        getItems().remove(key);
        return true;
    }

    public Map<String, Entry> getItems() {
        if (items == null) items = new HashMap<>();
        return items;
    }

    private boolean hasScope(String siteKey, String vodId, String sourceTitle) {
        return !TextUtils.isEmpty(siteKey) && !TextUtils.isEmpty(vodId) && !TextUtils.isEmpty(sourceTitle);
    }

    private String key(String siteKey, String vodId, String sourceTitle) {
        return siteKey + AppDatabase.SYMBOL + vodId + AppDatabase.SYMBOL + sourceKey(sourceTitle);
    }

    private String sourceKey(String sourceTitle) {
        return normalize(sourceTitle).replace(AppDatabase.SYMBOL, " ");
    }

    private String normalize(String text) {
        return text == null ? "" : text.replaceAll("[\\s·•:：\\-_/\\\\|()（）\\[\\]【】]+", "").trim().toLowerCase(Locale.ROOT);
    }

    public static class Entry {

        private int version;
        private int tmdbId;
        private String mediaType;
        private Integer seasonNumber;
        private Mode mode;
        private String sourceFingerprint;
        private int sourceEpisodeCount;
        private int tmdbSeasonEpisodeCount;
        private long updatedAt;

        public static Entry create(
                int tmdbId,
                String mediaType,
                Integer seasonNumber,
                Mode mode,
                String sourceFingerprint,
                int sourceEpisodeCount,
                int tmdbSeasonEpisodeCount) {
            Entry entry = new Entry();
            entry.version = VERSION;
            entry.tmdbId = tmdbId;
            entry.mediaType = mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT);
            entry.seasonNumber = seasonNumber;
            entry.mode = mode;
            entry.sourceFingerprint = sourceFingerprint == null ? "" : sourceFingerprint;
            entry.sourceEpisodeCount = Math.max(0, sourceEpisodeCount);
            entry.tmdbSeasonEpisodeCount = Math.max(0, tmdbSeasonEpisodeCount);
            entry.updatedAt = System.currentTimeMillis();
            return entry;
        }

        private boolean matches(int expectedTmdbId) {
            return version == VERSION
                    && tmdbId == expectedTmdbId
                    && "tv".equalsIgnoreCase(mediaType)
                    && mode != null
                    && (mode != Mode.MANUAL_SEASON || seasonNumber != null && seasonNumber >= 0)
                    && ((mode != Mode.MANUAL_FLAT && mode != Mode.MANUAL_MULTI_SLICE) || seasonNumber == null);
        }

        public int getVersion() {
            return version;
        }

        public int getTmdbId() {
            return tmdbId;
        }

        public String getMediaType() {
            return mediaType == null ? "" : mediaType;
        }

        public Integer getSeasonNumber() {
            return seasonNumber;
        }

        public Mode getMode() {
            return mode;
        }

        public String getSourceFingerprint() {
            return sourceFingerprint == null ? "" : sourceFingerprint;
        }

        public int getSourceEpisodeCount() {
            return sourceEpisodeCount;
        }

        public int getTmdbSeasonEpisodeCount() {
            return tmdbSeasonEpisodeCount;
        }

        public long getUpdatedAt() {
            return updatedAt;
        }
    }
}
