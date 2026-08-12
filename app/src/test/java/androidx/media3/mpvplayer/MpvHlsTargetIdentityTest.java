package androidx.media3.mpvplayer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class MpvHlsTargetIdentityTest {

    @Test
    public void repeatedVodPlaylistRewriteKeepsEveryProxyUriStable() {
        String playlist = """
                #EXTM3U
                #EXT-X-PLAYLIST-TYPE:VOD
                #EXT-X-MEDIA-SEQUENCE:0
                #EXTINF:6,
                segment-0.png
                #EXTINF:6,
                segment-1.png
                #EXT-X-ENDLIST
                """;

        String first = rewrite(playlist, 7);
        String refreshed = rewrite(playlist, 7);

        assertEquals(first, refreshed);
        assertNotEquals(first, rewrite(playlist, 8));
    }

    @Test
    public void identitySeparatesSessionUrlRoleCacheabilityAndVariant() {
        HlsPlaylistRewriter.Variant hd = new HlsPlaylistRewriter.Variant(
                8_000_000, 7_000_000, 1920, 1080);
        HlsPlaylistRewriter.Variant sd = new HlsPlaylistRewriter.Variant(
                2_000_000, 1_800_000, 960, 540);
        String base = MpvHlsTargetIdentity.stableId(
                3, "https://video.test/segment.png", true, hd,
                HlsPlaylistRewriter.UriRole.MEDIA_SEGMENT);

        assertEquals(base, MpvHlsTargetIdentity.stableId(
                3, "https://video.test/segment.png", true, hd,
                HlsPlaylistRewriter.UriRole.MEDIA_SEGMENT));
        assertNotEquals(base, MpvHlsTargetIdentity.stableId(
                4, "https://video.test/segment.png", true, hd,
                HlsPlaylistRewriter.UriRole.MEDIA_SEGMENT));
        assertNotEquals(base, MpvHlsTargetIdentity.stableId(
                3, "https://video.test/other.png", true, hd,
                HlsPlaylistRewriter.UriRole.MEDIA_SEGMENT));
        assertNotEquals(base, MpvHlsTargetIdentity.stableId(
                3, "https://video.test/segment.png", false, hd,
                HlsPlaylistRewriter.UriRole.MEDIA_SEGMENT));
        assertNotEquals(base, MpvHlsTargetIdentity.stableId(
                3, "https://video.test/segment.png", true, hd,
                HlsPlaylistRewriter.UriRole.OTHER));
        assertNotEquals(base, MpvHlsTargetIdentity.stableId(
                3, "https://video.test/segment.png", true, sd,
                HlsPlaylistRewriter.UriRole.MEDIA_SEGMENT));
    }

    private static String rewrite(String playlist, int sessionId) {
        return HlsPlaylistRewriter.rewrite(playlist, null,
                (uri, cacheable, context) -> {
                    String source = "https://video.test/" + uri;
                    String id = MpvHlsTargetIdentity.stableId(
                            sessionId, source, cacheable, context.variant(),
                            context.role());
                    return new HlsPlaylistRewriter.MappedUri(
                            source, "http://127.0.0.1/item?s=" + sessionId
                            + "&id=" + id);
                }).text();
    }
}
