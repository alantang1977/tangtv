package com.fongmi.android.tv.utils;

import com.google.common.net.HttpHeaders;

import java.util.LinkedHashMap;
import java.util.Map;

public final class WebSniffHeaders {

    private WebSniffHeaders() {
    }

    public static Map<String, String> forPage(Map<String, String> headers) {
        return forPage(headers, null);
    }

    public static Map<String, String> forPage(Map<String, String> headers, String fallbackUserAgent) {
        Map<String, String> result = new LinkedHashMap<>();
        boolean rejectedMediaUserAgent = false;
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                if (key == null || value == null) continue;
                if (HttpHeaders.USER_AGENT.equalsIgnoreCase(key) && !isBrowserUserAgent(value)) {
                    rejectedMediaUserAgent = true;
                    continue;
                }
                result.put(key, value);
            }
        }
        if (rejectedMediaUserAgent && isBrowserUserAgent(fallbackUserAgent)) result.put(HttpHeaders.USER_AGENT, browserUserAgent(fallbackUserAgent));
        return result;
    }

    static String browserUserAgent(String value) {
        if (value == null) return null;
        return value.replace("; wv)", ")").replace(" Version/4.0", "");
    }

    static boolean isBrowserUserAgent(String value) {
        String lower = value == null ? "" : value.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("mozilla/") && (lower.contains("applewebkit/") || lower.contains("gecko/"));
    }
}
