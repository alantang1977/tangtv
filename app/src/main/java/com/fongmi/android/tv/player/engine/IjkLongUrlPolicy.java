package com.fongmi.android.tv.player.engine;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class IjkLongUrlPolicy {

    static final int NATIVE_URL_LIMIT_BYTES = 1024;

    private IjkLongUrlPolicy() {
    }

    static Decision evaluate(String url) {
        int nativeBytes = url == null
                ? 0 : url.getBytes(StandardCharsets.UTF_8).length + 1;
        String lower = url == null ? "" : url.toLowerCase(Locale.US);
        boolean http = lower.startsWith("http://")
                || lower.startsWith("https://");
        return new Decision(http && nativeBytes > NATIVE_URL_LIMIT_BYTES,
                nativeBytes);
    }

    record Decision(boolean proxyRequired, int nativeBytes) {
    }
}
