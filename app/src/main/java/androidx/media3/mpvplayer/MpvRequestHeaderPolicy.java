package androidx.media3.mpvplayer;

import java.util.LinkedHashMap;
import java.util.Map;

final class MpvRequestHeaderPolicy {

    private static final String ACCEPT = "Accept";
    private static final String ORIGIN = "Origin";
    private static final String REFERER = "Referer";
    private static final String USER_AGENT = "User-Agent";

    private MpvRequestHeaderPolicy() {
    }

    static Resolved resolve(Map<String, String> sourceHeaders, String fallbackUserAgent) {
        Map<String, String> headers = copy(sourceHeaders);
        String userAgent = find(headers, USER_AGENT);
        if (isEmpty(userAgent) && !isEmpty(fallbackUserAgent)) {
            userAgent = fallbackUserAgent.trim();
            put(headers, USER_AGENT, userAgent);
        }
        if (isEmpty(find(headers, ACCEPT))) put(headers, ACCEPT, "*/*");
        return new Resolved(
                headers,
                userAgent,
                find(headers, REFERER),
                find(headers, ORIGIN));
    }

    private static Map<String, String> copy(Map<String, String> sourceHeaders) {
        Map<String, String> result = new LinkedHashMap<>();
        if (sourceHeaders == null) return result;
        for (Map.Entry<String, String> entry : sourceHeaders.entrySet()) {
            if (isEmpty(entry.getKey()) || entry.getValue() == null) continue;
            result.put(entry.getKey().trim(), entry.getValue().trim());
        }
        return result;
    }

    private static String find(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (name.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        }
        return null;
    }

    private static void put(Map<String, String> headers, String name, String value) {
        String existing = null;
        for (String key : headers.keySet()) {
            if (name.equalsIgnoreCase(key)) {
                existing = key;
                break;
            }
        }
        headers.put(existing == null ? name : existing, value);
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }

    record Resolved(
            Map<String, String> headers,
            String userAgent,
            String referer,
            String origin) {
    }
}
