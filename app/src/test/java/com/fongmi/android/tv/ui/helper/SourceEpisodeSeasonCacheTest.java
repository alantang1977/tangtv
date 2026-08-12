package com.fongmi.android.tv.ui.helper;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.TmdbEpisode;
import com.fongmi.android.tv.bean.Vod;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

public class SourceEpisodeSeasonCacheTest {

    @Test
    public void repeatedFlagAndVodResolutionScansEpisodesOnlyOnceUntilCleared() {
        AtomicInteger scans = new AtomicInteger();
        SourceEpisodeSeasonCache cache = new SourceEpisodeSeasonCache(episode -> {
            scans.incrementAndGet();
            return episode.getName().startsWith("S02") ? 2 : -1;
        });
        Flag flag = flag(episode("S02E01"), episode("S02E02"));
        Vod vod = vod(flag);

        assertEquals(2, cache.resolve(flag));
        assertEquals(2, cache.resolve(flag));
        assertEquals(2, cache.resolve(vod));
        assertEquals(2, cache.resolve(vod));
        assertEquals(2, scans.get());

        cache.clear();

        assertEquals(2, cache.resolve(vod));
        assertEquals(4, scans.get());
    }

    @Test
    public void explicitEpisodeNameWinsBoundTmdbSeason() {
        Episode episode = episode("Example.Show.S03E01");
        episode.setTmdbEpisode(new TmdbEpisode(1, "Episode", "", "", "", 0, 0, 0, 2));

        assertEquals(3, new SourceEpisodeSeasonCache().resolve(flag(episode)));
    }

    @Test
    public void clearingAfterTmdbBindingRecomputesPreviouslyUnknownSeason() {
        Episode episode = episode("Episode 1");
        Flag flag = flag(episode);
        SourceEpisodeSeasonCache cache = new SourceEpisodeSeasonCache();

        assertEquals(-1, cache.resolve(flag));
        episode.setTmdbEpisode(new TmdbEpisode(1, "Episode", "", "", "", 0, 0, 0, 4));
        assertEquals(-1, cache.resolve(flag));

        cache.clear();

        assertEquals(4, cache.resolve(flag));
    }

    @Test
    public void plainEpisodeNumberDoesNotBecomeASeason() {
        assertEquals(-1, new SourceEpisodeSeasonCache().resolve(flag(episode("109"))));
    }

    private static Episode episode(String name) {
        Episode episode = new Episode();
        episode.setName(name);
        return episode;
    }

    private static Flag flag(Episode... episodes) {
        Flag flag = new Flag("line");
        flag.getEpisodes().addAll(List.of(episodes));
        return flag;
    }

    private static Vod vod(Flag... flags) {
        Vod vod = new Vod();
        vod.setFlags(List.of(flags));
        return vod;
    }
}
