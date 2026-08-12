package com.fongmi.android.tv.remote;

import com.github.catvod.crawler.SpiderDebug;

/** Debug logging for the remote-trust feature. Disabled profiles are completely silent. */
public final class RemoteLog {

    private RemoteLog() {
    }

    public static void log(String format, Object... args) {
        if (RemoteStore.hasEnabledProfile()) SpiderDebug.log("remote", format, args);
    }
}
