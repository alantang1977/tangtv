package com.fongmi.android.tv.player.engine;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IjkLongUrlPolicyTest {

    @Test
    public void proxiesHttpUrlPastNativeLimit() {
        String url = urlWithNativeBytes(
                IjkLongUrlPolicy.NATIVE_URL_LIMIT_BYTES + 1);

        IjkLongUrlPolicy.Decision decision =
                IjkLongUrlPolicy.evaluate(url);

        assertTrue(decision.proxyRequired());
    }

    @Test
    public void keepsHttpUrlAtNativeLimitDirect() {
        String url = urlWithNativeBytes(
                IjkLongUrlPolicy.NATIVE_URL_LIMIT_BYTES);

        IjkLongUrlPolicy.Decision decision =
                IjkLongUrlPolicy.evaluate(url);

        assertFalse(decision.proxyRequired());
    }

    @Test
    public void doesNotProxyLongNonHttpSource() {
        IjkLongUrlPolicy.Decision decision = IjkLongUrlPolicy.evaluate(
                "file:///" + "x".repeat(2_000));

        assertFalse(decision.proxyRequired());
    }

    private static String urlWithNativeBytes(int nativeBytes) {
        String prefix = "https://example.com/";
        int prefixBytes = prefix.getBytes(StandardCharsets.UTF_8).length;
        return prefix + "x".repeat(nativeBytes - prefixBytes - 1);
    }
}
