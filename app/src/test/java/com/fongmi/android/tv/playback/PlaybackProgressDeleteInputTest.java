package com.fongmi.android.tv.playback;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlaybackProgressDeleteInputTest {

    @Test
    public void userDeletionAcceptsZeroCidAsLocalHistory() {
        assertTrue(PlaybackProgressWriter.canDeleteCid(0, true));
    }

    @Test
    public void syncDeletionStillRejectsZeroCidWithoutStableConfig() {
        assertFalse(PlaybackProgressWriter.canDeleteCid(0, false));
    }

    @Test
    public void deletionRejectsNegativeCid() {
        assertFalse(PlaybackProgressWriter.canDeleteCid(-1, true));
    }

    @Test
    public void syncDeletionAcceptsMappedPositiveCid() {
        assertTrue(PlaybackProgressWriter.canDeleteCid(7, false));
    }

    @Test
    public void userClearAllPreservesExplicitZeroCid() {
        PlaybackProgressDeleteInput input = new PlaybackProgressDeleteInput();
        input.cid = 0;

        assertEquals(0, PlaybackProgressWriter.targetCid(input, true));
    }

    @Test
    public void zeroCidUserDeleteReachesRequestValidation() {
        PlaybackProgressDeleteInput input = new PlaybackProgressDeleteInput();
        input.cid = 0;
        input.scope = "all";

        PlaybackProgressApplyResult result = PlaybackProgressWriter.deleteInternal(input, null, false, true);

        assertFalse(result.success);
        assertEquals("全量清理需要confirm=true", result.message);
    }

    @Test
    public void derivesPortableIdentityFromHistoryKey() {
        PlaybackProgressDeleteInput input = PlaybackProgressDeleteInput.listFromJson("""
                {"historyKey":"site@@@vod@@@99","action":"delete","deletedAt":123}
                """).get(0);

        assertEquals("site", input.siteKey);
        assertEquals("vod", input.vodId);
        assertEquals(123, input.deletedAt);
        assertTrue(input.isDeleteOperation());
    }

    @Test
    public void doesNotInventRemoteDeleteTimestamp() {
        PlaybackProgressDeleteInput input = PlaybackProgressDeleteInput.listFromJson("""
                {"historyKey":"site@@@vod@@@99","action":"delete"}
                """).get(0);

        assertEquals(0, input.deletedAt);
    }

    @Test
    public void unwrapsSingleDeleteEvent() {
        PlaybackProgressDeleteInput input = PlaybackProgressDeleteInput.listFromJson("""
                {"event":"playback.deleted","timestamp":456,"data":{"historyKey":"site@@@vod@@@99"}}
                """).get(0);

        assertEquals("site", input.siteKey);
        assertEquals("vod", input.vodId);
        assertEquals(456, input.deletedAt);
        assertTrue(input.isDeleteOperation());
    }
}
