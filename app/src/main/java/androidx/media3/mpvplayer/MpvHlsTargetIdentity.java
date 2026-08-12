package androidx.media3.mpvplayer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Builds stable local proxy identities for one HLS playback session. */
final class MpvHlsTargetIdentity {

    private MpvHlsTargetIdentity() {
    }

    static String stableId(
            int sessionId,
            String targetUrl,
            boolean cacheable,
            HlsPlaylistRewriter.Variant variant,
            HlsPlaylistRewriter.UriRole role) {
        StringBuilder identity = new StringBuilder(192);
        append(identity, Integer.toString(sessionId));
        append(identity, targetUrl);
        append(identity, cacheable ? "1" : "0");
        append(identity, role == null ? HlsPlaylistRewriter.UriRole.OTHER.name() : role.name());
        if (variant == null) {
            append(identity, "none");
        } else {
            append(identity, variant.kind().name());
            append(identity, Long.toString(variant.bandwidth()));
            append(identity, Long.toString(variant.averageBandwidth()));
            append(identity, Integer.toString(variant.width()));
            append(identity, Integer.toString(variant.height()));
        }
        return "h" + sha256(identity.toString());
    }

    private static void append(StringBuilder output, String value) {
        String safe = value == null ? "" : value;
        output.append(safe.length()).append(':').append(safe).append(';');
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                output.append(Character.forDigit((item >>> 4) & 0x0F, 16));
                output.append(Character.forDigit(item & 0x0F, 16));
            }
            return output.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
}
