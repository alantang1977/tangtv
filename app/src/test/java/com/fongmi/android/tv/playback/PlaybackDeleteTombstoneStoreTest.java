package com.fongmi.android.tv.playback;

import com.fongmi.android.tv.bean.PlaybackDeleteTombstone;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class PlaybackDeleteTombstoneStoreTest {

    @Test
    public void itemSiteAndAllScopesUseNewestMatchingCutoff() {
        PlaybackDeleteTombstone item = tombstone("item", "site", "vod", 100);
        PlaybackDeleteTombstone site = tombstone("site", "site", "", 200);
        PlaybackDeleteTombstone all = tombstone("all", "", "", 150);

        long cutoff = PlaybackDeleteTombstoneStore.latest(List.of(item, site, all),
                "config", 1, "site@@@vod@@@1", "site", "vod");
        long otherSite = PlaybackDeleteTombstoneStore.latest(List.of(item, site, all),
                "config", 1, "other@@@vod@@@1", "other", "vod");

        assertEquals(200, cutoff);
        assertEquals(150, otherSite);
    }

    @Test
    public void differentConfigDoesNotMatch() {
        PlaybackDeleteTombstone item = tombstone("item", "site", "vod", 100);

        assertEquals(0, PlaybackDeleteTombstoneStore.latest(List.of(item),
                "another", 1, "site@@@vod@@@1", "site", "vod"));
    }

    @Test
    public void olderDuplicateCannotReplaceNewerTombstone() {
        PlaybackDeleteTombstone newer = tombstone("item", "site", "vod", 200);
        PlaybackDeleteTombstone older = tombstone("item", "site", "vod", 100);

        assertSame(newer, PlaybackDeleteTombstoneStore.newest(newer, older));
        assertSame(newer, PlaybackDeleteTombstoneStore.newest(older, newer));
    }

    private PlaybackDeleteTombstone tombstone(String scope, String siteKey, String vodId, long deletedAt) {
        PlaybackDeleteTombstone tombstone = new PlaybackDeleteTombstone();
        tombstone.id = scope + siteKey + vodId;
        tombstone.configKey = "config";
        tombstone.scope = scope;
        tombstone.historyKey = "";
        tombstone.siteKey = siteKey;
        tombstone.vodId = vodId;
        tombstone.deletedAt = deletedAt;
        return tombstone;
    }
}
