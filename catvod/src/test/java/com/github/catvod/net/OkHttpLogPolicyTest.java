package com.github.catvod.net;

import org.junit.Test;

import java.io.IOException;
import java.net.URI;

import okhttp3.Headers;
import okhttp3.HttpUrl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OkHttpLogPolicyTest {

    @Test
    public void urlDropsPathQueryAndNestedCredentials() {
        HttpUrl url = HttpUrl.parse("http://127.0.0.1:1314/proxy/movie.mkv?url=https%3A%2F%2Fcdn.example%2Ffile%3Ftoken%3Dsecret&cookie=private");
        String safe = OkHttpLogPolicy.redactUrl(url);

        assertEquals("http://loopback:local/<redacted>.mkv", safe);
        assertFalse(safe.contains("movie"));
        assertFalse(safe.contains("secret"));
        assertFalse(safe.contains("private"));
    }

    @Test
    public void uriDropsPathQueryAndUserInfo() {
        URI uri = URI.create("https://user:password@cdn.example/private/file.mkv?token=secret");
        String safe = OkHttpLogPolicy.redactUri(uri);

        assertTrue(safe.matches("https://remote-[0-9a-f]{8}/<redacted>\\.mkv"));
        assertFalse(safe.contains("cdn.example"));
        assertFalse(safe.contains("password"));
        assertFalse(safe.contains("secret"));
        assertFalse(safe.contains("file.mkv"));
    }

    @Test
    public void requestMetadataAllowsOnlyValidRange() {
        Headers headers = new Headers.Builder()
                .add("rAnGe", "bytes=1048576-2097151")
                .add("Cookie", "session=private")
                .add("cookie", "second=private-two")
                .add("Authorization", "Bearer secret")
                .build();

        String safe = OkHttpLogPolicy.requestMetadata(headers);
        assertEquals(", range=bytes=1048576-2097151", safe);
        assertFalse(safe.contains("private"));
        assertFalse(safe.contains("private-two"));
        assertFalse(safe.contains("secret"));
    }

    @Test
    public void malformedRangeAndExceptionMessagesAreRedacted() {
        Headers headers = new Headers.Builder().add("Range", "bytes=abc-def;cookie=private").build();
        IOException error = new IOException("failed https://cdn.example/file?token=secret", new IllegalStateException("cookie=private"));

        assertEquals(", range=<redacted>", OkHttpLogPolicy.requestMetadata(headers));
        String chain = OkHttpLogPolicy.errorChain(error);
        assertTrue(chain.contains("IOException"));
        assertTrue(chain.contains("IllegalStateException"));
        assertFalse(chain.contains("secret"));
        assertFalse(chain.contains("private"));
    }
}
