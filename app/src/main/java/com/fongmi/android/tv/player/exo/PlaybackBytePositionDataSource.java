package com.fongmi.android.tv.player.exo;

import android.net.Uri;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import com.fongmi.android.tv.player.PlaybackResourceClassifier;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Collects byte positions for the media bitrate estimator.
 *
 * <p>The data source factory is shared by every Media3 load.  Consequently the
 * byte position is useful only while one, continuously opened Progressive data
 * source is active.  This class deliberately fails closed when the protocol or
 * continuity cannot be proven.</p>
 */
public final class PlaybackBytePositionDataSource implements DataSource {

    private static final long UNSET = C.POSITION_UNSET;
    private static final Pattern CONTENT_RANGE = Pattern.compile("bytes\\s+(\\d+)\\s*-\\s*(\\d+)\\s*/\\s*(\\d+|\\*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTENT_RANGE_TOTAL = Pattern.compile("bytes\\s+\\d+\\s*-\\s*\\d+\\s*/\\s*(\\d+|\\*)", Pattern.CASE_INSENSITIVE);
    private static final Object LOCK = new Object();
    private static final Set<Long> ACTIVE_TOKENS = new HashSet<>();

    private static long sequence;
    private static long sessionGeneration;
    private static long positionBytes = UNSET;
    private static long contentLengthBytes;
    private static long nextStreamToken;
    private static long activeStreamToken = UNSET;
    private static int activeOpenCount;
    private static int openedStreamCount;
    private static Eligibility eligibility = Eligibility.UNKNOWN;
    private static boolean continuityValid;
    private static PlaybackResourceClassifier.Classification resourceClassification;

    private final DataSource upstream;
    private long nextPositionBytes = UNSET;
    private long rangeEndBytes = UNSET;
    private long requestedEndBytes = UNSET;
    private long streamToken = UNSET;
    private long registrationGeneration = Long.MIN_VALUE;
    private volatile boolean registered;
    private volatile boolean endOfInput;

    PlaybackBytePositionDataSource(DataSource upstream) {
        this.upstream = upstream;
    }

    static void resetSession() {
        synchronized (LOCK) {
            sequence++;
            sessionGeneration++;
            positionBytes = UNSET;
            contentLengthBytes = 0;
            activeStreamToken = UNSET;
            ACTIVE_TOKENS.clear();
            activeOpenCount = 0;
            openedStreamCount = 0;
            eligibility = Eligibility.UNKNOWN;
            continuityValid = false;
            resourceClassification = null;
        }
    }

    public static PlaybackResourceClassifier.Classification latestResourceClassification() {
        synchronized (LOCK) {
            return resourceClassification;
        }
    }

    public static long resourceSessionSequence() {
        synchronized (LOCK) {
            return sessionGeneration;
        }
    }

    static Snapshot snapshot() {
        synchronized (LOCK) {
            return new Snapshot(sequence, positionBytes, contentLengthBytes, eligibility, activeStreamToken, activeOpenCount, continuityValid);
        }
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        upstream.addTransferListener(transferListener);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        // DataSource implementations are expected to close before reopening, but
        // treat a violation as a continuity break instead of retaining a stale
        // active-open reservation.
        if (registered) releaseOpen();
        OpenRegistration registration = registerOpen();
        streamToken = registration.token();
        registrationGeneration = registration.generation();
        registered = true;
        endOfInput = false;
        nextPositionBytes = Math.max(0, dataSpec.position);
        rangeEndBytes = UNSET;
        requestedEndBytes = resolveRequestedEnd(dataSpec);
        try {
            long length = upstream.open(dataSpec);
            Map<String, List<String>> headers;
            Uri resolvedUri;
            try {
                headers = upstream.getResponseHeaders();
                resolvedUri = upstream.getUri();
            } catch (RuntimeException ignored) {
                // A missing response fact must disable slope, not interrupt the
                // foreground load that can still be served by the upstream.
                headers = Map.of();
                resolvedUri = null;
            }
            Classification classification = classify(
                    dataSpec.uri.toString(),
                    dataSpec.position,
                    length,
                    headers,
                    resolvedUri == null ? "" : resolvedUri.toString());
            ContentRange range = parseContentRange(headers);
            rangeEndBytes = range == null ? UNSET : range.endBytes();
            long totalLength = resolveTotalLength(dataSpec, length, headers);
            PlaybackResourceClassifier.Classification resource = PlaybackResourceClassifier.classifyDataSource(
                    dataSpec.uri.toString(),
                    resolvedUri == null ? null : resolvedUri.toString(),
                    contentType(headers),
                    headers);
            completeOpen(registration, dataSpec, classification, totalLength, resource);
            return length;
        } catch (IOException | RuntimeException error) {
            failOpen(registration);
            throw error;
        }
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        final int read;
        try {
            read = upstream.read(buffer, offset, length);
        } catch (IOException | RuntimeException error) {
            invalidateForFailure();
            throw error;
        }
        if (read < 0) {
            endOfInput = true;
            return read;
        }
        if (read == 0) return read;

        synchronized (LOCK) {
            if (!registered || !ACTIVE_TOKENS.contains(streamToken) || streamToken != activeStreamToken || eligibility != Eligibility.PROGRESSIVE || !continuityValid) return read;
            if (nextPositionBytes < 0 || positionBytes != nextPositionBytes) {
                invalidateLocked(Eligibility.INELIGIBLE);
                return read;
            }
            long next = safeAddPosition(nextPositionBytes, read);
            if (next < 0 || (rangeEndBytes >= 0 && rangeEndBytes < Long.MAX_VALUE && next > rangeEndBytes + 1) || (requestedEndBytes >= 0 && next > requestedEndBytes)) {
                invalidateLocked(Eligibility.INELIGIBLE);
                return read;
            }
            nextPositionBytes = next;
            positionBytes = next;
        }
        return read;
    }

    @Nullable
    @Override
    public Uri getUri() {
        return upstream.getUri();
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return upstream.getResponseHeaders();
    }

    @Override
    public void close() throws IOException {
        try {
            upstream.close();
        } finally {
            if (registered && !endOfInput) invalidateForFailure();
            releaseOpen();
        }
    }

    static long parseContentRangeTotal(Map<String, List<String>> headers) {
        if (headers == null || headers.isEmpty()) return 0;
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() == null || !"Content-Range".equalsIgnoreCase(entry.getKey())) continue;
            if (entry.getValue() == null) continue;
            for (String value : entry.getValue()) {
                if (value == null) continue;
                Matcher matcher = CONTENT_RANGE_TOTAL.matcher(value.trim());
                if (!matcher.matches() || "*".equals(matcher.group(1))) continue;
                try {
                    return Long.parseLong(matcher.group(1));
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
        }
        return 0;
    }

    /** Exposed to package tests so the conservative classifier can be checked without I/O. */
    static Eligibility classifyEligibility(DataSpec dataSpec, long openedLength, Map<String, List<String>> headers, @Nullable Uri resolvedUri) {
        return classify(dataSpec.uri.toString(), dataSpec.position, openedLength, headers, resolvedUri == null ? "" : resolvedUri.toString()).eligibility();
    }

    /** String form used by local JVM tests, where framework Uri methods are not mocked. */
    static Eligibility classifyEligibility(String requestedUri, long position, long openedLength, Map<String, List<String>> headers, @Nullable String resolvedUri) {
        return classify(requestedUri, position, openedLength, headers, resolvedUri == null ? "" : resolvedUri).eligibility();
    }

    private OpenRegistration registerOpen() {
        synchronized (LOCK) {
            long token = ++nextStreamToken;
            long generation = sessionGeneration;
            boolean first = openedStreamCount == 0;
            openedStreamCount++;
            sequence++;
            if (first) {
                activeStreamToken = token;
                eligibility = Eligibility.UNKNOWN;
                continuityValid = false;
                positionBytes = UNSET;
            } else {
                invalidateLocked(Eligibility.INELIGIBLE);
            }
            ACTIVE_TOKENS.add(token);
            activeOpenCount = ACTIVE_TOKENS.size();
            if (activeOpenCount > 1) invalidateLocked(Eligibility.INELIGIBLE);
            return new OpenRegistration(token, first, generation);
        }
    }

    private void completeOpen(OpenRegistration registration, DataSpec dataSpec, Classification classification, long totalLength,
                              PlaybackResourceClassifier.Classification resource) {
        synchronized (LOCK) {
            if (registration.generation() == sessionGeneration && resource != null) resourceClassification = resource;
            if (totalLength > contentLengthBytes) contentLengthBytes = totalLength;
            if (!registration.first() || eligibility == Eligibility.INELIGIBLE) {
                if (!registration.first()) invalidateLocked(Eligibility.INELIGIBLE);
                return;
            }
            eligibility = classification.eligibility();
            continuityValid = classification.eligibility() == Eligibility.PROGRESSIVE && classification.rangeValid();
            if (continuityValid) {
                positionBytes = Math.max(0, dataSpec.position);
                nextPositionBytes = positionBytes;
            } else {
                positionBytes = UNSET;
            }
        }
    }

    private void failOpen(OpenRegistration registration) {
        synchronized (LOCK) {
            releaseOpenLocked(registration.token(), registration.generation());
            if (registration.first() && eligibility != Eligibility.INELIGIBLE) {
                eligibility = Eligibility.UNKNOWN;
                continuityValid = false;
                positionBytes = UNSET;
            } else {
                invalidateLocked(Eligibility.INELIGIBLE);
            }
        }
        registered = false;
    }

    private void releaseOpen() {
        if (!registered) return;
        synchronized (LOCK) {
            releaseOpenLocked(streamToken, registrationGeneration);
        }
        registered = false;
    }

    private static void releaseOpenLocked(long token, long generation) {
        if (generation != sessionGeneration || !ACTIVE_TOKENS.remove(token)) return;
        activeOpenCount = ACTIVE_TOKENS.size();
    }

    private void invalidateForFailure() {
        synchronized (LOCK) {
            if (streamToken == activeStreamToken) invalidateLocked(Eligibility.INELIGIBLE);
        }
    }

    private static void invalidateLocked(Eligibility reason) {
        eligibility = reason;
        continuityValid = false;
        positionBytes = UNSET;
    }

    private static Classification classify(String requestedUri, long requestedPosition, long openedLength, Map<String, List<String>> headers, String resolvedUri) {
        ContentRange range = parseContentRange(headers);
        String requested = normalizedUri(requestedUri);
        String resolved = normalizedUri(resolvedUri);
        String contentType = normalizedContentType(headers);
        String combined = requested + " " + resolved;

        if (!hasProgressiveTransport(requested) && !hasProgressiveTransport(resolved)) return Classification.unknown();
        if (containsManifestIndicator(combined) || isManifestMime(contentType)) return Classification.ineligible();
        if (containsSegmentIndicator(combined) || isSegmentMime(contentType)) return Classification.ineligible();
        if (requestedPosition != 0) return Classification.ineligible();
        if (hasContentRangeHeader(headers) && range == null) return Classification.ineligible();
        if (range != null && (range.startBytes() != requestedPosition
                || range.endBytes() < range.startBytes()
                || (range.totalBytes() > 0 && range.totalBytes() <= range.endBytes()))) return Classification.ineligible();

        boolean knownProgressive = isProgressiveMime(contentType) || hasProgressiveExtension(requested) || hasProgressiveExtension(resolved);
        if (!knownProgressive || openedLength == 0) return Classification.unknown();
        return new Classification(Eligibility.PROGRESSIVE, true);
    }

    private static boolean containsManifestIndicator(String value) {
        return value.contains(".m3u8") || value.contains(".m3u") || value.contains(".mpd") || value.contains("application/vnd.apple.mpegurl") || value.contains("application/dash+xml") || value.contains("format=hls") || value.contains("format=dash") || value.contains("type=m3u8") || value.contains("type=mpd");
    }

    private static boolean containsSegmentIndicator(String value) {
        return value.contains(".m4s") || value.contains(".cmfv") || value.contains(".cmfa") || value.contains(".ts") || value.contains(".aac") || value.contains(".ac3") || value.contains(".ec3") || value.contains(".vtt") || value.contains(".webvtt") || value.contains("/segment") || value.contains("/seg-") || value.contains("/chunk") || value.contains("/part") || value.contains("/fragment") || value.contains("/init-") || value.contains("segment=") || value.contains("chunk=") || value.contains("part=") || value.contains("fragment=");
    }

    private static boolean hasProgressiveTransport(String value) {
        int separator = value.indexOf(':');
        if (separator <= 0) return false;
        String scheme = value.substring(0, separator);
        return scheme.equals("http") || scheme.equals("https") || scheme.equals("file") || scheme.equals("content") || scheme.equals("asset") || scheme.equals("data") || scheme.equals("android.resource");
    }

    private static boolean isManifestMime(String contentType) {
        return contentType.startsWith("application/vnd.apple.mpegurl") || contentType.startsWith("application/x-mpegurl") || contentType.startsWith("audio/mpegurl") || contentType.startsWith("application/dash+xml");
    }

    private static boolean isSegmentMime(String contentType) {
        return contentType.startsWith("video/mp2t") || contentType.startsWith("video/iso.segment") || contentType.startsWith("application/fragment+mp4") || contentType.startsWith("audio/aac") || contentType.startsWith("audio/ac3") || contentType.startsWith("audio/eac3");
    }

    private static boolean isProgressiveMime(String contentType) {
        return contentType.startsWith("video/mp4") || contentType.startsWith("video/x-matroska") || contentType.startsWith("video/webm") || contentType.startsWith("video/quicktime") || contentType.startsWith("video/x-msvideo") || contentType.startsWith("video/mpeg") || contentType.startsWith("video/x-flv") || contentType.startsWith("video/3gpp") || contentType.startsWith("audio/mpeg") || contentType.startsWith("audio/mp4") || contentType.startsWith("audio/ogg") || contentType.startsWith("audio/flac") || contentType.startsWith("audio/wav") || contentType.startsWith("audio/x-wav");
    }

    private static boolean hasProgressiveExtension(String value) {
        int query = value.indexOf('?');
        String path = query >= 0 ? value.substring(0, query) : value;
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot <= slash || dot == path.length() - 1) return false;
        String extension = path.substring(dot + 1);
        return extension.equals("mp4") || extension.equals("m4v") || extension.equals("mkv") || extension.equals("webm") || extension.equals("mov") || extension.equals("avi") || extension.equals("flv") || extension.equals("wmv") || extension.equals("mpg") || extension.equals("mpeg") || extension.equals("3gp") || extension.equals("ogv") || extension.equals("mp3") || extension.equals("flac") || extension.equals("wav") || extension.equals("ogg");
    }

    private static String normalizedUri(@Nullable Uri uri) {
        return uri == null ? "" : uri.toString().toLowerCase(Locale.US);
    }

    private static String normalizedUri(@Nullable String uri) {
        return uri == null ? "" : uri.toLowerCase(Locale.US);
    }

    private static String normalizedContentType(Map<String, List<String>> headers) {
        if (headers == null || headers.isEmpty()) return "";
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() == null || !"Content-Type".equalsIgnoreCase(entry.getKey()) || entry.getValue() == null) continue;
            for (String value : entry.getValue()) {
                if (value == null) continue;
                int separator = value.indexOf(';');
                return (separator >= 0 ? value.substring(0, separator) : value).trim().toLowerCase(Locale.US);
            }
        }
        return "";
    }

    private static String contentType(Map<String, List<String>> headers) {
        return normalizedContentType(headers);
    }

    @Nullable
    private static ContentRange parseContentRange(Map<String, List<String>> headers) {
        if (headers == null || headers.isEmpty()) return null;
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() == null || !"Content-Range".equalsIgnoreCase(entry.getKey()) || entry.getValue() == null) continue;
            for (String value : entry.getValue()) {
                if (value == null) continue;
                Matcher matcher = CONTENT_RANGE.matcher(value.trim());
                if (!matcher.matches()) continue;
                try {
                    long start = Long.parseLong(matcher.group(1));
                    long end = Long.parseLong(matcher.group(2));
                    long total = "*".equals(matcher.group(3)) ? 0 : Long.parseLong(matcher.group(3));
                    return new ContentRange(start, end, total);
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static boolean hasContentRangeHeader(Map<String, List<String>> headers) {
        if (headers == null || headers.isEmpty()) return false;
        for (String key : headers.keySet()) {
            if (key != null && "Content-Range".equalsIgnoreCase(key)) return true;
        }
        return false;
    }

    private static long resolveTotalLength(DataSpec dataSpec, long openedLength, Map<String, List<String>> headers) {
        long contentRangeTotal = parseContentRangeTotal(headers);
        if (contentRangeTotal > 0) return contentRangeTotal;
        if (dataSpec.length != C.LENGTH_UNSET) return dataSpec.position == 0 ? dataSpec.length : 0;
        if (openedLength == C.LENGTH_UNSET || openedLength <= 0 || dataSpec.position > Long.MAX_VALUE - openedLength) return 0;
        return dataSpec.position + openedLength;
    }

    private static long resolveRequestedEnd(DataSpec dataSpec) {
        if (dataSpec.length == C.LENGTH_UNSET || dataSpec.length < 0 || dataSpec.position < 0 || dataSpec.position > Long.MAX_VALUE - dataSpec.length) return UNSET;
        return dataSpec.position + dataSpec.length;
    }

    private static long safeAddPosition(long position, int delta) {
        if (position < 0 || delta <= 0 || position > Long.MAX_VALUE - delta) return UNSET;
        return position + delta;
    }

    enum Eligibility {
        UNKNOWN("unknown"),
        PROGRESSIVE("progressive"),
        INELIGIBLE("ineligible");

        private final String label;

        Eligibility(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    record Snapshot(long sequence, long positionBytes, long contentLengthBytes, Eligibility eligibility, long streamToken, int activeOpenCount, boolean continuityValid) {

        /** Missing protocol facts are deliberately treated as unknown and therefore ineligible. */
        Snapshot(long sequence, long positionBytes, long contentLengthBytes) {
            this(sequence, positionBytes, contentLengthBytes, Eligibility.UNKNOWN, UNSET, 0, false);
        }

        boolean byteSlopeEligible() {
            return eligibility == Eligibility.PROGRESSIVE && continuityValid && activeOpenCount == 1 && streamToken != UNSET;
        }

        boolean isByteSlopeEligible() {
            return byteSlopeEligible();
        }

        int activeStreams() {
            return activeOpenCount;
        }
    }

    private record OpenRegistration(long token, boolean first, long generation) {
    }

    private record Classification(Eligibility eligibility, boolean rangeValid) {

        static Classification unknown() {
            return new Classification(Eligibility.UNKNOWN, false);
        }

        static Classification ineligible() {
            return new Classification(Eligibility.INELIGIBLE, false);
        }
    }

    private record ContentRange(long startBytes, long endBytes, long totalBytes) {
    }

    static final class Factory implements DataSource.Factory {

        private final DataSource.Factory upstreamFactory;

        Factory(DataSource.Factory upstreamFactory) {
            this.upstreamFactory = upstreamFactory;
        }

        @Override
        public DataSource createDataSource() {
            return new PlaybackBytePositionDataSource(upstreamFactory.createDataSource());
        }
    }
}
