package com.fongmi.android.tv.ui.helper;

import com.fongmi.android.tv.bean.TmdbSeasonMatchCache;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Pure policy for resolving source episodes to TMDB seasons without guessing season one. */
public final class TmdbSeasonResolver {

    public enum Status {
        RESOLVED,
        MULTI_SLICE,
        FLAT,
        AMBIGUOUS
    }

    public enum Source {
        REQUEST,
        MANUAL,
        MANUAL_FLAT,
        MANUAL_MULTI_SLICE,
        EXPLICIT,
        EXPLICIT_CONFLICT,
        TITLE,
        SINGLE_SEASON,
        EPISODE_COUNT,
        FLAT_EPISODE_KEYS,
        ALL_SEASON_COUNTS,
        NONE
    }

    private TmdbSeasonResolver() {
    }

    public static Resolution resolve(
            int requestSeason,
            TmdbSeasonMatchCache.Entry manualBinding,
            List<Integer> explicitSourceSeasons,
            int titleSeason,
            List<Integer> tmdbSeasons,
            Map<Integer, Integer> seasonCounts,
            int sourceEpisodeCount) {
        return resolve(requestSeason, manualBinding, explicitSourceSeasons, titleSeason,
                tmdbSeasons, seasonCounts, sourceEpisodeCount, null);
    }

    public static Resolution resolve(
            int requestSeason,
            TmdbSeasonMatchCache.Entry manualBinding,
            List<Integer> explicitSourceSeasons,
            int titleSeason,
            List<Integer> tmdbSeasons,
            Map<Integer, Integer> seasonCounts,
            int sourceEpisodeCount,
            List<Integer> sourceEpisodeNumbers) {
        List<Integer> seasons = distinctNonNegative(tmdbSeasons);
        List<Integer> sliceableSeasons = EpisodeSeasonPolicy.sliceableSeasons(seasons);
        if (requestSeason >= 0) {
            if (contains(seasons, requestSeason)) return Resolution.resolved(requestSeason, Source.REQUEST, "request_season");
            return Resolution.ambiguous(Source.REQUEST, "requested_season_missing_from_tmdb");
        }

        if (manualBinding != null && manualBinding.getMode() == TmdbSeasonMatchCache.Mode.MANUAL_FLAT) {
            return Resolution.flat(Source.MANUAL_FLAT, "manual_flat");
        }
        if (seasons.isEmpty()) return Resolution.ambiguous(Source.NONE, "tmdb_seasons_empty");
        if (manualBinding != null && manualBinding.getMode() == TmdbSeasonMatchCache.Mode.MANUAL_MULTI_SLICE) {
            List<Integer> coveredSeasons = sourceEpisodeNumbers == null
                    ? EpisodeSeasonPolicy.coveredSeasonsByEpisodeCount(sourceEpisodeCount, sliceableSeasons, seasonCounts)
                    : EpisodeSeasonPolicy.mappedSeasonsByEpisodeNumbers(sourceEpisodeNumbers, sliceableSeasons, seasonCounts);
            return !coveredSeasons.isEmpty()
                    ? Resolution.multiSlice(coveredSeasons, Source.MANUAL_MULTI_SLICE, "manual_multi_slice")
                    : Resolution.ambiguous(Source.MANUAL_MULTI_SLICE, "manual_multi_slice_stale");
        }

        Resolution manual = resolveManual(manualBinding, seasons);
        if (manual != null) return manual;

        List<Integer> explicit = distinctNonNegative(explicitSourceSeasons);
        if (explicit.size() > 1) return Resolution.ambiguous(Source.EXPLICIT_CONFLICT, "multiple_explicit_seasons");
        if (explicit.size() == 1) {
            int season = explicit.get(0);
            if (titleSeason >= 0 && titleSeason != season) {
                return Resolution.ambiguous(Source.EXPLICIT_CONFLICT, "title_and_source_season_conflict");
            }
            return contains(seasons, season)
                    ? Resolution.resolved(season, Source.EXPLICIT, "explicit_source_season")
                    : Resolution.ambiguous(Source.EXPLICIT, "explicit_season_missing_from_tmdb");
        }

        if (titleSeason >= 0) {
            return contains(seasons, titleSeason)
                    ? Resolution.resolved(titleSeason, Source.TITLE, "title_season")
                    : Resolution.ambiguous(Source.TITLE, "title_season_missing_from_tmdb");
        }

        List<Integer> ordinarySeasons = new ArrayList<>();
        for (Integer season : seasons) if (season != null && season > 0) ordinarySeasons.add(season);
        if (ordinarySeasons.size() == 1) return Resolution.resolved(ordinarySeasons.get(0), Source.SINGLE_SEASON, "single_ordinary_season");
        if (ordinarySeasons.isEmpty() && seasons.size() == 1 && seasons.get(0) == 0) {
            return Resolution.resolved(0, Source.SINGLE_SEASON, "specials_only");
        }

        List<Integer> exactCountMatches = exactCountMatches(sliceableSeasons.isEmpty() ? seasons : sliceableSeasons, seasonCounts, sourceEpisodeCount);
        if (exactCountMatches.size() == 1) {
            return Resolution.resolved(exactCountMatches.get(0), Source.EPISODE_COUNT, "unique_episode_count");
        }
        if (EpisodeSeasonPolicy.canSliceBySeasonCounts(sourceEpisodeCount, sliceableSeasons, seasonCounts)) {
            return Resolution.multiSlice(sliceableSeasons, Source.ALL_SEASON_COUNTS, "all_season_counts");
        }

        List<Integer> keyedSeasons = EpisodeSeasonPolicy.mappedSeasonsByEpisodeNumbers(
                sourceEpisodeNumbers, sliceableSeasons, seasonCounts);
        if (keyedSeasons.size() > 1) {
            return Resolution.multiSlice(keyedSeasons, Source.FLAT_EPISODE_KEYS, "flat_episode_keys");
        }

        if (exactCountMatches.size() > 1) {
            return Resolution.ambiguous(Source.EPISODE_COUNT, "duplicate_episode_counts");
        }

        return Resolution.ambiguous(Source.NONE, "insufficient_season_evidence");
    }

    private static Resolution resolveManual(TmdbSeasonMatchCache.Entry binding, List<Integer> tmdbSeasons) {
        if (binding == null) return null;
        if (binding.getMode() == TmdbSeasonMatchCache.Mode.MANUAL_FLAT) {
            return Resolution.flat(Source.MANUAL_FLAT, "manual_flat");
        }
        Integer season = binding.getSeasonNumber();
        if (binding.getMode() == TmdbSeasonMatchCache.Mode.MANUAL_SEASON && season != null && contains(tmdbSeasons, season)) {
            return Resolution.resolved(season, Source.MANUAL, "manual_season");
        }
        return null;
    }

    private static List<Integer> exactCountMatches(List<Integer> seasons, Map<Integer, Integer> seasonCounts, int sourceEpisodeCount) {
        List<Integer> matches = new ArrayList<>();
        if (sourceEpisodeCount <= 0 || seasonCounts == null || seasonCounts.isEmpty()) return matches;
        for (Integer season : seasons) {
            if (seasonCounts.getOrDefault(season, 0) == sourceEpisodeCount) matches.add(season);
        }
        return matches;
    }

    private static List<Integer> distinctNonNegative(List<Integer> values) {
        Set<Integer> distinct = new LinkedHashSet<>();
        if (values != null) for (Integer value : values) if (value != null && value >= 0) distinct.add(value);
        return List.copyOf(distinct);
    }

    private static boolean contains(List<Integer> seasons, int season) {
        return season >= 0 && seasons.contains(season);
    }

    public static final class Resolution {

        private final Status status;
        private final Integer selectedSeason;
        private final List<Integer> availableSeasons;
        private final Source source;
        private final String reason;

        private Resolution(Status status, Integer selectedSeason, List<Integer> availableSeasons, Source source, String reason) {
            this.status = status;
            this.selectedSeason = selectedSeason;
            this.availableSeasons = availableSeasons == null ? List.of() : List.copyOf(availableSeasons);
            this.source = source;
            this.reason = reason == null ? "" : reason;
        }

        private static Resolution resolved(int season, Source source, String reason) {
            return new Resolution(Status.RESOLVED, season, List.of(season), source, reason);
        }

        private static Resolution multiSlice(List<Integer> seasons, Source source, String reason) {
            return new Resolution(Status.MULTI_SLICE, null, seasons, source, reason);
        }

        private static Resolution flat(Source source, String reason) {
            return new Resolution(Status.FLAT, null, List.of(), source, reason);
        }

        private static Resolution ambiguous(Source source, String reason) {
            return new Resolution(Status.AMBIGUOUS, null, List.of(), source, reason);
        }

        public Status getStatus() {
            return status;
        }

        public Integer getSelectedSeason() {
            return selectedSeason;
        }

        public List<Integer> getAvailableSeasons() {
            return availableSeasons;
        }

        public Source getSource() {
            return source;
        }

        public String getReason() {
            return reason;
        }
    }
}
