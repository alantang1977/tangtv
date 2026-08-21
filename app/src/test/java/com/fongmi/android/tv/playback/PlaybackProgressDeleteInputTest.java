package com.fongmi.android.tv.playback;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PlaybackProgressDeleteInputTest {

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
