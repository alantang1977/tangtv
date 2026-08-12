package com.fongmi.android.tv.api.loader;

final class CspClassLoadingPolicy {

    private static final String PROTOBUF_PREFIX = "com.google.protobuf.";

    private CspClassLoadingPolicy() {
    }

    static boolean isChildFirst(String name) {
        return name != null && name.startsWith(PROTOBUF_PREFIX);
    }
}
