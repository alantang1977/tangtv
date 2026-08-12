package com.fongmi.android.tv.api.loader;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CspClassLoadingPolicyTest {

    @Test
    public void protobufClassesLoadFromCspJarFirst() {
        assertTrue(CspClassLoadingPolicy.isChildFirst("com.google.protobuf.Internal"));
        assertTrue(CspClassLoadingPolicy.isChildFirst("com.google.protobuf.SingleFieldBuilder"));
    }

    @Test
    public void hostInterfacesKeepParentFirstLoading() {
        assertFalse(CspClassLoadingPolicy.isChildFirst("com.github.catvod.crawler.Spider"));
        assertFalse(CspClassLoadingPolicy.isChildFirst("okhttp3.OkHttpClient"));
        assertFalse(CspClassLoadingPolicy.isChildFirst(null));
    }
}
