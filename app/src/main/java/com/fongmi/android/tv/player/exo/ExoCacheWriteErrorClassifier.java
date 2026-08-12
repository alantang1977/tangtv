package com.fongmi.android.tv.player.exo;

import androidx.media3.datasource.cache.CacheDataSink;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;

final class ExoCacheWriteErrorClassifier {

    private ExoCacheWriteErrorClassifier() {
    }

    static boolean isDiskWriteFailure(Throwable error) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = error; current != null && visited.add(current); current = current.getCause()) {
            if (current instanceof CacheDataSink.CacheDataSinkException) return true;
            if (hasNoSpaceMarker(current.getMessage())) return true;
        }
        return false;
    }

    private static boolean hasNoSpaceMarker(String message) {
        if (message == null || message.isBlank()) return false;
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("enospc") || normalized.contains("no space left on device") || normalized.contains("errno 28");
    }
}
