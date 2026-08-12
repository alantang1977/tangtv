package com.fongmi.android.tv.playback;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PlaybackRemoteSyncPayloadTest {

    @Test
    public void parsesLegacyUpsertResponse() {
        PlaybackRemoteSyncPayload payload = PlaybackRemoteSyncPayload.fromJson("""
                {
                  "items": [{
                    "configKey": "abc",
                    "siteKey": "site",
                    "vodId": "vod",
                    "vodName": "Title",
                    "episodeName": "EP1",
                    "positionMs": 1000,
                    "durationMs": 2000,
                    "updatedAt": 100
                  }],
                  "nextSince": 101
                }
                """);

        assertEquals(1, payload.upserts.size());
        assertEquals(0, payload.deletions.size());
        assertEquals("101", payload.nextSince);
        assertEquals("site", payload.upserts.get(0).siteKey);
    }

    @Test
    public void parsesDeletedArrayAndUnifiedDeleteItems() {
        PlaybackRemoteSyncPayload payload = PlaybackRemoteSyncPayload.fromJson("""
                {
                  "items": [
                    {"action":"delete","configKey":"abc","historyKey":"site@@@one@@@9","deletedAt":200},
                    {"configKey":"abc","siteKey":"site","vodId":"two","vodName":"Two","episodeName":"EP2","positionMs":20,"durationMs":40,"updatedAt":210}
                  ],
                  "deleted": [
                    {"configKey":"abc","siteKey":"site","vodId":"three","deletedAt":220}
                  ]
                }
                """);

        assertEquals(1, payload.upserts.size());
        assertEquals(2, payload.deletions.size());
        assertEquals("site", payload.deletions.get(0).siteKey);
        assertTrue(payload.deletions.stream().anyMatch(item -> "one".equals(item.vodId)));
        assertTrue(payload.deletions.stream().anyMatch(item -> "three".equals(item.vodId)));
    }

    @Test
    public void limitsDeletesBeforeUpserts() {
        PlaybackRemoteSyncPayload payload = PlaybackRemoteSyncPayload.fromJson("""
                {
                  "deleted": [
                    {"siteKey":"site","vodId":"one","deletedAt":1},
                    {"siteKey":"site","vodId":"two","deletedAt":2}
                  ],
                  "items": [
                    {"siteKey":"site","vodId":"three","vodName":"Three","episodeName":"EP","positionMs":1,"durationMs":2,"updatedAt":3}
                  ]
                }
                """);

        payload.limit(2);

        assertEquals(2, payload.deletions.size());
        assertEquals(0, payload.upserts.size());
    }

    @Test
    public void parsesWrappedDeleteEvent() {
        PlaybackRemoteSyncPayload payload = PlaybackRemoteSyncPayload.fromJson("""
                {
                  "event":"playback.deleted",
                  "timestamp":321,
                  "data":{"configKey":"abc","historyKey":"site@@@vod@@@7"}
                }
                """);

        assertEquals(0, payload.upserts.size());
        assertEquals(1, payload.deletions.size());
        assertEquals(321, payload.deletions.get(0).deletedAt);
        assertEquals("vod", payload.deletions.get(0).vodId);
    }

    @Test
    public void parsesWrappedChangeOperations() {
        PlaybackRemoteSyncPayload payload = PlaybackRemoteSyncPayload.fromJson("""
                {
                  "changes": [
                    {"op":"delete","timestamp":400,"data":{"configKey":"abc","historyKey":"site@@@old@@@7"}},
                    {"op":"upsert","timestamp":410,"data":{"configKey":"abc","siteKey":"site","vodId":"new","vodName":"New","episodeName":"EP","positionMs":10,"durationMs":20}}
                  ]
                }
                """);

        assertEquals(1, payload.deletions.size());
        assertEquals(1, payload.upserts.size());
        assertEquals(400, payload.deletions.get(0).deletedAt);
        assertEquals(410, payload.upserts.get(0).updatedAt);
    }

    @Test
    public void propagatesEnvelopeTimestampToDataArray() {
        PlaybackRemoteSyncPayload payload = PlaybackRemoteSyncPayload.fromJson("""
                {
                  "event":"playback.deleted",
                  "timestamp":500,
                  "data":[{"siteKey":"site","vodId":"vod"}]
                }
                """);

        assertEquals(1, payload.deletions.size());
        assertEquals(500, payload.deletions.get(0).deletedAt);
    }

    @Test
    public void keepsDataRecordAlongsideDeletedArray() {
        PlaybackRemoteSyncPayload payload = PlaybackRemoteSyncPayload.fromJson("""
                {
                  "deleted":[{"siteKey":"site","vodId":"old","deletedAt":600}],
                  "data":{"siteKey":"site","vodId":"new","vodName":"New","episodeName":"EP","positionMs":1,"durationMs":2,"updatedAt":601}
                }
                """);

        assertEquals(1, payload.deletions.size());
        assertEquals(1, payload.upserts.size());
    }
}
