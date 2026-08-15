package com.fongmi.android.tv.ui.helper;

import com.fongmi.android.tv.bean.Episode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Stable digest for the source episode line and the TMDB season counts. */
public final class EpisodeSeasonSnapshot {

    private EpisodeSeasonSnapshot() {
    }

    public static String fingerprint(List<Episode> episodes, Map<Integer, Integer> seasonCounts) {
        MessageDigest digest = sha256();
        update(digest, "episodes");
        if (episodes != null) {
            update(digest, Integer.toString(episodes.size()));
            for (Episode episode : episodes) {
                if (episode == null) {
                    update(digest, "<null>");
                    continue;
                }
                update(digest, episode.getName());
                update(digest, episode.getDesc());
                update(digest, episode.getUrl());
                update(digest, Integer.toString(episode.getNumber()));
            }
        } else {
            update(digest, "<null>");
        }
        update(digest, "seasons");
        List<Map.Entry<Integer, Integer>> counts = new ArrayList<>();
        if (seasonCounts != null) counts.addAll(seasonCounts.entrySet());
        counts.sort(Comparator.comparingInt(entry -> entry.getKey() == null ? Integer.MIN_VALUE : entry.getKey()));
        for (Map.Entry<Integer, Integer> entry : counts) {
            update(digest, entry.getKey() == null ? "<null>" : entry.getKey().toString());
            update(digest, entry.getValue() == null ? "<null>" : entry.getValue().toString());
        }
        return hex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void update(MessageDigest digest, String value) {
        String safe = Objects.toString(value, "");
        byte[] bytes = safe.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }
}
