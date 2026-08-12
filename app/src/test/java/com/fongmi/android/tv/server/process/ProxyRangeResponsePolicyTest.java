package com.fongmi.android.tv.server.process;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ProxyRangeResponsePolicyTest {

    @Test
    public void partialOpenRangePublishesExactStart() {
        assertEquals(646_775_888L, ProxyRangeResponsePolicy.resolveStart(206, "bytes=646775888-"));
    }

    @Test
    public void partialClosedRangePublishesExactStart() {
        assertEquals(100L, ProxyRangeResponsePolicy.resolveStart(206, "bytes=100-199"));
    }

    @Test
    public void nonPartialResponseDoesNotClaimRangeOffset() {
        assertEquals(-1L, ProxyRangeResponsePolicy.resolveStart(200, "bytes=100-"));
    }

    @Test
    public void malformedOrMultipleRangeIsRejected() {
        assertEquals(-1L, ProxyRangeResponsePolicy.resolveStart(206, "bytes=-100"));
        assertEquals(-1L, ProxyRangeResponsePolicy.resolveStart(206, "bytes=200-100"));
        assertEquals(-1L, ProxyRangeResponsePolicy.resolveStart(206, "bytes=0-1,4-5"));
        assertEquals(-1L, ProxyRangeResponsePolicy.resolveStart(206, "bytes=999999999999999999999-"));
    }
}
