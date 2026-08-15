package androidx.media3.mpvplayer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayDeque;
import java.util.Deque;

/** Converts mpv's GPU timestamp passes into a playback-specific frame-budget load. */
final class MpvGpuLoadTracker {

    private static final int AVERAGE_WINDOW = 10;
    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    private final Deque<Double> recentLoads = new ArrayDeque<>();
    private double recentLoadSum;

    Snapshot update(String json, double contentFps, double displayFps) {
        PassSummary fresh = parsePasses(json, "fresh");
        PassSummary redraw = parsePasses(json, "redraw");
        if (!fresh.available() && !redraw.available()) return Snapshot.unavailable();

        double sourceRate = positive(contentFps) ? contentFps
                : positive(displayFps) ? displayFps : 0;
        if (!positive(sourceRate)) return Snapshot.timingOnly(fresh, redraw);

        double redrawRate = positive(displayFps)
                ? Math.max(0, displayFps - sourceRate) : 0;
        double load = (fresh.totalLastNs() * sourceRate
                + redraw.totalLastNs() * redrawRate) / NANOS_PER_SECOND * 100.0;
        load = Math.max(0, load);
        recentLoads.addLast(load);
        recentLoadSum += load;
        while (recentLoads.size() > AVERAGE_WINDOW) {
            recentLoadSum -= recentLoads.removeFirst();
        }
        return new Snapshot(true, load,
                recentLoads.isEmpty() ? load : recentLoadSum / recentLoads.size(),
                fresh.totalLastNs(), redraw.totalLastNs(),
                fresh.passCount() + redraw.passCount(),
                dominant(fresh, redraw));
    }

    void reset() {
        recentLoads.clear();
        recentLoadSum = 0;
    }

    private static PassSummary parsePasses(String json, String key) {
        if (json == null || json.isBlank()) return PassSummary.unavailable();
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) return PassSummary.unavailable();
            JsonElement value = root.getAsJsonObject().get(key);
            if (value == null || !value.isJsonArray()) return PassSummary.unavailable();
            JsonArray passes = value.getAsJsonArray();
            if (passes.isEmpty()) return PassSummary.unavailable();
            long total = 0;
            long dominantNs = 0;
            String dominant = "";
            int count = 0;
            for (JsonElement element : passes) {
                if (!element.isJsonObject()) continue;
                JsonObject pass = element.getAsJsonObject();
                long last = Math.max(0, longValue(pass.get("last")));
                if (last <= 0) continue;
                total = saturatingAdd(total, last);
                count++;
                if (last > dominantNs) {
                    dominantNs = last;
                    dominant = stringValue(pass.get("desc"));
                }
            }
            return count == 0 ? PassSummary.unavailable()
                    : new PassSummary(true, total, count, dominant, dominantNs);
        } catch (Throwable ignored) {
            return PassSummary.unavailable();
        }
    }

    private static long longValue(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) return 0;
        try {
            return value.getAsLong();
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static String stringValue(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) return "";
        try {
            return value.getAsString();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static DominantPass dominant(PassSummary fresh, PassSummary redraw) {
        PassSummary value = fresh.dominantNs() >= redraw.dominantNs() ? fresh : redraw;
        return new DominantPass(value.dominant(), value.dominantNs());
    }

    private static boolean positive(double value) {
        return Double.isFinite(value) && value > 0;
    }

    private static long saturatingAdd(long first, long second) {
        return first > Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second;
    }

    record Snapshot(boolean available, double loadPercent, double averagePercent,
                    long freshNs, long redrawNs, int passCount,
                    DominantPass dominantPass) {

        static Snapshot unavailable() {
            return new Snapshot(false, 0, 0, 0, 0, 0,
                    new DominantPass("", 0));
        }

        static Snapshot timingOnly(PassSummary fresh, PassSummary redraw) {
            return new Snapshot(true, Double.NaN, Double.NaN,
                    fresh.totalLastNs(), redraw.totalLastNs(),
                    fresh.passCount() + redraw.passCount(), dominant(fresh, redraw));
        }
    }

    private record PassSummary(boolean available, long totalLastNs, int passCount,
                               String dominant, long dominantNs) {

        static PassSummary unavailable() {
            return new PassSummary(false, 0, 0, "", 0);
        }
    }

    record DominantPass(String description, long timeNs) {
    }
}
