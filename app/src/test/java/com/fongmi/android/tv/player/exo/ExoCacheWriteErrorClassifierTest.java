package com.fongmi.android.tv.player.exo;

import androidx.media3.datasource.cache.CacheDataSink;

import org.junit.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ExoCacheWriteErrorClassifierTest {

    @Test
    public void cacheDataSinkFailureIsAlwaysAWriteFailure() {
        Throwable error = new CacheDataSink.CacheDataSinkException(new IOException("write failed"));

        assertTrue(ExoCacheWriteErrorClassifier.isDiskWriteFailure(error));
    }

    @Test
    public void nestedNoSpaceMarkersAreRecognized() {
        String[] messages = {"ENOSPC", "No space left on device", "open failed: errno 28"};

        assertTrue(ExoCacheWriteErrorClassifier.isDiskWriteFailure(new IOException(messages[0])));
        for (String message : messages) {
            Throwable error = new IllegalStateException("wrapper", new IOException(message));
            assertTrue(ExoCacheWriteErrorClassifier.isDiskWriteFailure(error));
        }
    }

    @Test
    public void ordinaryNetworkErrorsDoNotOpenDiskCircuit() {
        assertFalse(ExoCacheWriteErrorClassifier.isDiskWriteFailure(new SocketTimeoutException("timeout")));
        assertFalse(ExoCacheWriteErrorClassifier.isDiskWriteFailure(new IOException("HTTP 503")));
        assertFalse(ExoCacheWriteErrorClassifier.isDiskWriteFailure(null));
    }

    @Test
    public void cyclicCauseChainTerminates() {
        Throwable first = new IOException("first");
        Throwable second = new IOException("second");
        first.initCause(second);
        second.initCause(first);

        assertFalse(ExoCacheWriteErrorClassifier.isDiskWriteFailure(first));
    }
}
