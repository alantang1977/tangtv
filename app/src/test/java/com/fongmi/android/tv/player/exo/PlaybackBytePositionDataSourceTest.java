package com.fongmi.android.tv.player.exo;

import org.junit.After;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class PlaybackBytePositionDataSourceTest {

    @After
    public void resetCoordinator() {
        PlaybackBytePositionDataSource.resetSession();
    }

    @Test
    public void contentRangeExposesWholeFileLength() {
        Map<String, List<String>> headers = Map.of("content-range", List.of("bytes 1048576-2097151/60000000000"));

        assertEquals(60_000_000_000L, PlaybackBytePositionDataSource.parseContentRangeTotal(headers));
    }

    @Test
    public void invalidOrUnknownContentRangeIsIgnored() {
        assertEquals(0, PlaybackBytePositionDataSource.parseContentRangeTotal(Map.of("Content-Range", List.of("bytes */*"))));
        assertEquals(0, PlaybackBytePositionDataSource.parseContentRangeTotal(Map.of("Content-Length", List.of("123"))));
    }

    @Test
    public void confirmedProgressiveRangeIsEligible() {
        Map<String, List<String>> headers = Map.of(
                "Content-Type", List.of("video/mp4"),
                "Content-Range", List.of("bytes 0-999/1000"));

        assertEquals(PlaybackBytePositionDataSource.Eligibility.PROGRESSIVE,
                PlaybackBytePositionDataSource.classifyEligibility("https://media.example/movie.mp4", 0, 1000, headers, "https://media.example/movie.mp4"));
    }

    @Test
    public void unknownProtocolDoesNotQualify() {
        Map<String, List<String>> headers = Map.of("Content-Type", List.of("application/octet-stream"));

        assertEquals(PlaybackBytePositionDataSource.Eligibility.UNKNOWN,
                PlaybackBytePositionDataSource.classifyEligibility("https://media.example/play?id=42", 0, 1000, headers, "https://media.example/play?id=42"));
    }

    @Test
    public void nonProgressiveTransportDoesNotQualifyByExtensionAlone() {
        assertEquals(PlaybackBytePositionDataSource.Eligibility.UNKNOWN,
                PlaybackBytePositionDataSource.classifyEligibility(
                        "rtsp://media.example/movie.mp4",
                        0,
                        1000,
                        Map.of("Content-Type", List.of("video/mp4")),
                        "rtsp://media.example/movie.mp4"));
    }

    @Test
    public void manifestsAndSegmentsAreRejectedEvenWithMediaMime() {
        assertEquals(PlaybackBytePositionDataSource.Eligibility.INELIGIBLE,
                PlaybackBytePositionDataSource.classifyEligibility("https://media.example/live/index.m3u8", 0, 1000, Map.of("Content-Type", List.of("application/vnd.apple.mpegurl")), "https://media.example/live/index.m3u8"));
        assertEquals(PlaybackBytePositionDataSource.Eligibility.INELIGIBLE,
                PlaybackBytePositionDataSource.classifyEligibility("https://media.example/segment-0001.mp4", 0, 1000, Map.of("Content-Type", List.of("video/mp4")), "https://media.example/segment-0001.mp4"));
    }

    @Test
    public void nonZeroRangeStartIsRejectedAsSeekOrGap() {
        Map<String, List<String>> headers = Map.of(
                "Content-Type", List.of("video/mp4"),
                "Content-Range", List.of("bytes 1024-2047/100000"));

        assertEquals(PlaybackBytePositionDataSource.Eligibility.INELIGIBLE,
                PlaybackBytePositionDataSource.classifyEligibility("https://media.example/movie.mp4", 1024, 1024, headers, "https://media.example/movie.mp4"));
    }

    @Test
    public void malformedContentRangeIsNotTreatedAsContinuous() {
        assertEquals(PlaybackBytePositionDataSource.Eligibility.INELIGIBLE,
                PlaybackBytePositionDataSource.classifyEligibility(
                        "https://media.example/movie.mp4",
                        0,
                        1024,
                        Map.of("Content-Type", List.of("video/mp4"), "Content-Range", List.of("bytes */*")),
                        "https://media.example/movie.mp4"));
    }

    @Test
    public void concurrentSnapshotIsNotSlopeEligible() {
        PlaybackBytePositionDataSource.Snapshot snapshot = new PlaybackBytePositionDataSource.Snapshot(
                1,
                0,
                0,
                PlaybackBytePositionDataSource.Eligibility.PROGRESSIVE,
                1,
                2,
                true);

        assertFalse(snapshot.byteSlopeEligible());
    }

}
