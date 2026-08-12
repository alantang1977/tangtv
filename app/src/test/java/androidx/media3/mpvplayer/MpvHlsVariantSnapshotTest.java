package androidx.media3.mpvplayer;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class MpvHlsVariantSnapshotTest {

    @Test
    public void downgradeLadderContainsOnlyDeduplicatedOrdinaryStreams() {
        List<HlsPlaylistRewriter.VariantEntry> declared = List.of(
                entry("low", 4_000_000L, 3_500_000L,
                        HlsPlaylistRewriter.VariantKind.STREAM),
                entry("iframe", 1_000_000L, 900_000L,
                        HlsPlaylistRewriter.VariantKind.I_FRAME),
                entry("image", 200_000L, 180_000L,
                        HlsPlaylistRewriter.VariantKind.IMAGE),
                entry("duplicate", 4_000_000L, 3_800_000L,
                        HlsPlaylistRewriter.VariantKind.STREAM),
                entry("high", 12_000_000L, 10_000_000L,
                        HlsPlaylistRewriter.VariantKind.STREAM));

        List<MpvHlsProxy.HlsVariant> ladder =
                MpvHlsProxy.buildVariantLadder(declared);

        assertEquals(2, ladder.size());
        assertEquals(4_000_000L, ladder.get(0).selectionBitsPerSecond());
        assertEquals(12_000_000L, ladder.get(1).selectionBitsPerSecond());
    }

    @Test
    public void peakBandwidthWinsAndAverageIsOnlyFallback() {
        List<MpvHlsProxy.HlsVariant> ladder = MpvHlsProxy.buildVariantLadder(
                List.of(
                        entry("average-only", 0, 6_000_000L,
                                HlsPlaylistRewriter.VariantKind.STREAM),
                        entry("peak", 8_000_000L, 20_000_000L,
                                HlsPlaylistRewriter.VariantKind.STREAM)));

        assertEquals(6_000_000L, ladder.get(0).selectionBitsPerSecond());
        assertEquals(8_000_000L, ladder.get(1).selectionBitsPerSecond());
    }

    @Test
    public void nativeSelectedTrackBitrateResolvesTheActualVariant() {
        List<MpvHlsProxy.HlsVariant> ladder = List.of(
                new MpvHlsProxy.HlsVariant(4_000_000L, 3_500_000L, 1280, 720),
                new MpvHlsProxy.HlsVariant(8_000_000L, 7_000_000L, 1920, 1080),
                new MpvHlsProxy.HlsVariant(12_000_000L, 10_000_000L, 3840, 2160));

        MpvHlsProxy.HlsVariant selected = MpvHlsProxy.resolveSelectedVariant(
                ladder, 8_000_000L);

        assertEquals(8_000_000L, selected.selectionBitsPerSecond());
        assertEquals(1920, selected.width());
        assertNull(MpvHlsProxy.resolveSelectedVariant(ladder, 0));
    }

    @Test
    public void nativeVariantMissingFromProxyLadderRemainsUsable() {
        MpvHlsProxy.HlsVariant selected = MpvHlsProxy.resolveSelectedVariant(
                List.of(), 6_000_000L);

        assertEquals(6_000_000L, selected.selectionBitsPerSecond());
        assertEquals(0, selected.width());
    }

    private static HlsPlaylistRewriter.VariantEntry entry(
            String uri,
            long bandwidth,
            long average,
            HlsPlaylistRewriter.VariantKind kind) {
        return new HlsPlaylistRewriter.VariantEntry(
                uri,
                new HlsPlaylistRewriter.Variant(
                        bandwidth, average, 1920, 1080, kind));
    }
}
