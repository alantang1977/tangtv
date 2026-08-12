package com.fongmi.android.tv.server.process;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ProxyRangeResponsePolicy {

    static final String HEADER_RANGE_START = "X-WebHTV-Proxy-Range-Start";
    private static final Pattern SINGLE_BYTE_RANGE = Pattern.compile("(?i)^bytes=(\\d+)-(\\d*)$");

    private ProxyRangeResponsePolicy() {
    }

    static long resolveStart(int statusCode, String requestedRange) {
        if (statusCode != 206 || requestedRange == null) return -1;
        Matcher matcher = SINGLE_BYTE_RANGE.matcher(requestedRange.trim());
        if (!matcher.matches()) return -1;
        try {
            long start = Long.parseLong(matcher.group(1));
            String endText = matcher.group(2);
            if (!endText.isEmpty() && Long.parseLong(endText) < start) return -1;
            return start;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }
}
