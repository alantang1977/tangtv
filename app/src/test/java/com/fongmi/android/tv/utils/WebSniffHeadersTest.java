package com.fongmi.android.tv.utils;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WebSniffHeadersTest {

    @Test
    public void mediaUserAgentIsNotAppliedToWebPage() {
        Map<String, String> input = new LinkedHashMap<>();
        input.put("User-Agent", "Lavf/59.27.100");
        input.put("Referer", "https://www.4gtv.tv/");

        Map<String, String> result = WebSniffHeaders.forPage(input);

        assertFalse(result.containsKey("User-Agent"));
        assertEquals("https://www.4gtv.tv/", result.get("Referer"));
    }

    @Test
    public void browserUserAgentIsPreserved() {
        Map<String, String> result = WebSniffHeaders.forPage(Map.of(
                "User-Agent", "Mozilla/5.0 AppleWebKit/537.36 Chrome/126 Safari/537.36"));

        assertTrue(result.containsKey("User-Agent"));
    }

    @Test
    public void mediaUserAgentFallsBackToMobileBrowserIdentity() {
        Map<String, String> result = WebSniffHeaders.forPage(
                Map.of("User-Agent", "Lavf/59.27.100"),
                "Mozilla/5.0 (Linux; Android 15; Device; wv) AppleWebKit/537.36 Version/4.0 Chrome/126 Mobile Safari/537.36");

        assertEquals("Mozilla/5.0 (Linux; Android 15; Device) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36", result.get("User-Agent"));
    }
}
