package com.fongmi.android.tv.setting;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProxySettingTest {

    @Test
    public void suggestedHostRejectsIpLiterals() {
        assertFalse(ProxySetting.isSuggestedHost("8.8.8.8"));
        assertFalse(ProxySetting.isSuggestedHost("223.5.5.5"));
        assertFalse(ProxySetting.isSuggestedHost("127.0.0.1"));
        assertFalse(ProxySetting.isSuggestedHost("999.5.5.5"));
        assertFalse(ProxySetting.isSuggestedHost("2001:4860:4860::8888"));
        assertFalse(ProxySetting.isSuggestedHost("[2001:db8::1]"));
        assertFalse(ProxySetting.isSuggestedHost("fe80::1%wlan0"));
    }

    @Test
    public void suggestedHostKeepsDomainNames() {
        assertTrue(ProxySetting.isSuggestedHost("github.com"));
        assertTrue(ProxySetting.isSuggestedHost("123.example.com"));
        assertTrue(ProxySetting.isSuggestedHost("cdn.example.co.uk."));
        assertFalse(ProxySetting.isSuggestedHost("localhost"));
        assertFalse(ProxySetting.isSuggestedHost("intranet"));
    }
}
