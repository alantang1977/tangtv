package com.fongmi.android.tv.playback;

import android.text.TextUtils;

import java.nio.charset.StandardCharsets;

import okhttp3.Request;

final class PlaybackHttpHeaders {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    private static final String ENCODING = "percent-utf-8";

    private PlaybackHttpHeaders() {
    }

    static void header(Request.Builder builder, String name, String value) {
        if (builder == null || TextUtils.isEmpty(name) || TextUtils.isEmpty(value)) return;
        if (isAsciiHeaderValue(value)) {
            builder.header(name, value);
            return;
        }
        builder.header(name, percentEncode(value));
        builder.header(name + "-Encoding", ENCODING);
    }

    private static boolean isAsciiHeaderValue(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c > 0x7E) return false;
        }
        return true;
    }

    private static String percentEncode(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder builder = new StringBuilder(bytes.length * 3);
        for (byte item : bytes) {
            int valueByte = item & 0xFF;
            if (isUnreserved(valueByte)) {
                builder.append((char) valueByte);
            } else {
                builder.append('%');
                builder.append(HEX[valueByte >> 4]);
                builder.append(HEX[valueByte & 0x0F]);
            }
        }
        return builder.toString();
    }

    private static boolean isUnreserved(int value) {
        return value >= 'A' && value <= 'Z'
                || value >= 'a' && value <= 'z'
                || value >= '0' && value <= '9'
                || value == '-' || value == '_' || value == '.' || value == '~';
    }
}
