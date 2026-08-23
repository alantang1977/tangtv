package com.fongmi.android.tv.player;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;

public class PlayerManagerLifecycleSourceTest {

    @Test
    public void releasedEngineClassificationIsNullSafeForLateCallbacks() throws Exception {
        String source = readPlayerManager();

        assertTrue("isLive must tolerate a late callback after engine release",
                source.contains("public boolean isLive() {\n        return engine != null && engine.isLive();"));
        assertTrue("isVod must tolerate a late callback after engine release",
                source.contains("public boolean isVod() {\n        return engine != null && engine.isVod();"));
    }

    private static String readPlayerManager() throws IOException {
        Path root = Path.of("").toAbsolutePath();
        Path source = root.resolve(Path.of(
                "app", "src", "main", "java", "com", "fongmi", "android", "tv", "player", "PlayerManager.java"));
        if (!Files.exists(source)) {
            source = root.resolve(Path.of(
                    "src", "main", "java", "com", "fongmi", "android", "tv", "player", "PlayerManager.java"));
        }
        return Files.readString(source, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
