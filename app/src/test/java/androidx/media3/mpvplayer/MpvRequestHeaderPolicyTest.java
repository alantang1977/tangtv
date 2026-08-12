package androidx.media3.mpvplayer;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

public class MpvRequestHeaderPolicyTest {

    @Test
    public void missingRefererAndOriginRemainMissing() {
        MpvRequestHeaderPolicy.Resolved resolved = MpvRequestHeaderPolicy.resolve(
                Map.of("User-Agent", "Browser UA"),
                "Fallback UA");

        assertNull(resolved.referer());
        assertNull(resolved.origin());
        assertFalse(hasHeader(resolved.headers(), "Referer"));
        assertFalse(hasHeader(resolved.headers(), "Origin"));
    }

    @Test
    public void explicitRefererAndOriginArePreserved() {
        Map<String, String> source = new LinkedHashMap<>();
        source.put("referer", "https://page.example/");
        source.put("ORIGIN", "https://page.example");

        MpvRequestHeaderPolicy.Resolved resolved = MpvRequestHeaderPolicy.resolve(source, "Fallback UA");

        assertEquals("https://page.example/", resolved.referer());
        assertEquals("https://page.example", resolved.origin());
        assertEquals("https://page.example/", resolved.headers().get("referer"));
        assertEquals("https://page.example", resolved.headers().get("ORIGIN"));
    }

    @Test
    public void sourceUserAgentWinsAndOnlyGenericAcceptIsAdded() {
        MpvRequestHeaderPolicy.Resolved resolved = MpvRequestHeaderPolicy.resolve(
                Map.of("user-agent", "Source UA"),
                "Fallback UA");

        assertEquals("Source UA", resolved.userAgent());
        assertEquals("Source UA", resolved.headers().get("user-agent"));
        assertEquals("*/*", resolved.headers().get("Accept"));
        assertEquals(2, resolved.headers().size());
    }

    @Test
    public void fallbackUserAgentDoesNotCreateAuthorizationHeaders() {
        MpvRequestHeaderPolicy.Resolved resolved = MpvRequestHeaderPolicy.resolve(Map.of(), "Fallback UA");

        assertEquals("Fallback UA", resolved.userAgent());
        assertEquals("Fallback UA", resolved.headers().get("User-Agent"));
        assertNull(resolved.referer());
        assertNull(resolved.origin());
    }

    private static boolean hasHeader(Map<String, String> headers, String name) {
        return headers.keySet().stream().anyMatch(name::equalsIgnoreCase);
    }
}
