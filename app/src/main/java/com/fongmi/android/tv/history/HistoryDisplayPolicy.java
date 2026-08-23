package com.fongmi.android.tv.history;

import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.TmdbSeasonProgress;
import com.fongmi.android.tv.playback.TmdbSeasonProgressStore;
import com.fongmi.android.tv.title.MediaTitleParser;
import com.fongmi.android.tv.ui.helper.EpisodeSeasonPolicy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class HistoryDisplayPolicy {

    private static final MediaTitleParser TITLE_PARSER = new MediaTitleParser();

    private HistoryDisplayPolicy() {
    }

    public static List<History> project(List<History> source, boolean aggregateByTmdb) {
        List<History> items = new ArrayList<>();
        if (source != null) for (History item : source) if (item != null) items.add(item);
        if (!aggregateByTmdb) return sort(items);

        Map<String, History> aggregated = new HashMap<>();
        List<History> result = new ArrayList<>();
        for (History sourceItem : items) {
            History item = decorateKnownSeason(sourceItem);
            String identity = tmdbIdentity(item);
            if (identity.isEmpty()) {
                result.add(item);
                continue;
            }
            History existing = aggregated.get(identity);
            if (existing == null || item.getCreateTime() >= existing.getCreateTime()) aggregated.put(identity, item);
        }
        result.addAll(aggregated.values());
        return sort(result);
    }

    /**
     * Expands shared-source quarterly snapshots before the normal TMDB
     * aggregation.  A History row remains the route record; each snapshot is
     * only a display/playback projection and never gets persisted as a second
     * Room row.
     */
    public static List<History> project(List<History> source, List<TmdbSeasonProgress> progress,
                                        boolean aggregateByTmdb) {
        if (!aggregateByTmdb || progress == null || progress.isEmpty()) {
            return project(source, aggregateByTmdb);
        }
        List<History> base = new ArrayList<>();
        Map<String, History> routes = new HashMap<>();
        if (source != null) {
            for (History item : source) {
                if (item == null) continue;
                base.add(item);
                routes.put(routeKey(item.getCid(), item.getTmdbId(), item.getKey()), item);
            }
        }
        for (TmdbSeasonProgress snapshot : progress) {
            if (snapshot == null || snapshot.episodeNumber <= 0 || snapshot.seasonNumber < 0
                    || snapshot.sourceHistoryKey.isEmpty()) continue;
            History route = routes.get(routeKey(snapshot.cid, snapshot.tmdbId, snapshot.sourceHistoryKey));
            if (route == null) continue;
            History item = route.copy();
            TmdbSeasonProgressStore.apply(item, snapshot);
            item.setDisplayIdentity(tmdbIdentity(item));
            item.setVodName(seasonDisplayName(item.getVodName(), snapshot.seasonNumber));
            base.add(item);
        }
        return project(base, true);
    }

    private static String routeKey(int cid, int tmdbId, String historyKey) {
        return cid + ":" + tmdbId + ":" + (historyKey == null ? "" : historyKey);
    }

    private static String seasonDisplayName(String title, int season) {
        String value = title == null ? "" : title.trim();
        int parsedSeason = EpisodeSeasonPolicy.resolveSourceSeason(value);
        if (parsedSeason == season) return value;
        if (parsedSeason >= 0) {
            value = TITLE_PARSER.cleanTitle(value);
            if (parsedSeason == 0) {
                value = value.replaceAll("(?i)(特别篇|特別篇|\\bspecials\\b)", " ")
                        .replaceAll("\\s+", " ").trim();
            }
        }
        return value + (season == 0 ? " 特别篇" : " 第" + season + "季");
    }

    private static History decorateKnownSeason(History source) {
        if (source == null || !"tv".equalsIgnoreCase(source.getMediaType())) return source;
        int season = source.getTmdbSeasonNumber();
        boolean known = season > 0 || season == 0 && source.getTmdbEpisodeNumber() > 0;
        if (!known) return source;
        History item = source.copy();
        item.setVodName(seasonDisplayName(item.getVodName(), season));
        return item;
    }

    public static String tmdbIdentity(History item) {
        if (item == null || item.getTmdbId() <= 0) return "";
        String mediaType = item.getMediaType() == null ? "" : item.getMediaType().trim().toLowerCase(Locale.ROOT);
        if (!mediaType.equals("movie") && !mediaType.equals("tv")) return "";
        String identity = mediaType + ":" + item.getTmdbId();
        if (!mediaType.equals("tv")) return identity;
        int season = item.getTmdbSeasonNumber();
        boolean knownSeason = season > 0 || (season == 0 && item.getTmdbEpisodeNumber() > 0);
        if (knownSeason) return identity + ":season:" + season;
        String key = item.getKey();
        return key == null || key.isEmpty() ? "" : "source:" + key;
    }

    private static List<History> sort(List<History> items) {
        items.sort((first, second) -> Long.compare(second.getCreateTime(), first.getCreateTime()));
        return items;
    }
}
