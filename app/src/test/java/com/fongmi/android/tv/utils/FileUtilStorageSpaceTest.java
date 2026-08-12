package com.fongmi.android.tv.utils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FileUtilStorageSpaceTest {

    @Test
    public void validFactsArePreserved() {
        FileUtil.StorageSpace space = FileUtil.StorageSpace.of(256, 1024);

        assertTrue(space.available());
        assertEquals(256, space.availableBytes());
        assertEquals(1024, space.totalBytes());
    }

    @Test
    public void invalidFactsBecomeUnavailable() {
        FileUtil.StorageSpace[] spaces = {
                FileUtil.StorageSpace.of(-1, 1024),
                FileUtil.StorageSpace.of(1025, 1024),
                FileUtil.StorageSpace.of(0, 0)
        };

        for (FileUtil.StorageSpace space : spaces) {
            assertFalse(space.available());
            assertEquals(0, space.availableBytes());
            assertEquals(0, space.totalBytes());
        }
    }
}
