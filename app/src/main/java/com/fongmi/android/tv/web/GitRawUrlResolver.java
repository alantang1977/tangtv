package com.fongmi.android.tv.web;

import android.net.Uri;
import android.text.TextUtils;

import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class GitRawUrlResolver {

    private GitRawUrlResolver() {
    }

    public static RawUrl resolve(String url) {
        if (TextUtils.isEmpty(url)) return null;
        try {
            Uri source = Uri.parse(url);
            String scheme = source.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return null;
            String host = source.getHost();
            if (TextUtils.isEmpty(host)) return null;
            Uri uri = clean(source);
            String lowerHost = host.toLowerCase(Locale.ROOT);
            String path = path(uri);
            RawUrl result = githubRaw(uri, lowerHost, path);
            if (result != null) return result;
            result = github(uri, lowerHost, path);
            if (result != null) return result;
            result = gist(uri, lowerHost, path);
            if (result != null) return result;
            result = cnb(uri, lowerHost, path);
            if (result != null) return result;
            result = dashRaw(uri, lowerHost, path);
            if (result != null) return result;
            result = knownSimple(uri, lowerHost, path);
            if (result != null) return result;
            return giteaLike(uri, lowerHost, path);
        } catch (Throwable e) {
            return null;
        }
    }

    public static String github(String owner, String repo, String ref, String path) {
        if (TextUtils.isEmpty(owner) || TextUtils.isEmpty(repo) || TextUtils.isEmpty(ref) || TextUtils.isEmpty(path)) return "";
        return "https://raw.githubusercontent.com/" + encode(owner) + "/" + encode(repo) + "/" + encodePath(ref) + "/" + encodePath(trimSlash(path));
    }

    public static String cnb(String baseUrl, String owner, String repo, String ref, String path) {
        if (TextUtils.isEmpty(owner) || TextUtils.isEmpty(repo) || TextUtils.isEmpty(ref) || TextUtils.isEmpty(path)) return "";
        String base = TextUtils.isEmpty(baseUrl) ? "https://cnb.cool" : baseUrl.replaceAll("/+$", "");
        return base + "/" + encodePath(owner) + "/" + encodePath(repo) + "/-/git/raw/" + encodePath(ref) + "/" + encodePath(trimSlash(path));
    }

    private static RawUrl githubRaw(Uri uri, String host, String path) {
        if (!"raw.githubusercontent.com".equals(host)) return null;
        List<String> segments = uri.getPathSegments();
        if (segments.size() < 4) return null;
        String scope = "github:" + segments.get(0) + "/" + segments.get(1);
        return new RawUrl(uri.toString(), uri.toString(), scope, path);
    }

    private static RawUrl github(Uri uri, String host, String path) {
        if (!"github.com".equals(host)) return null;
        List<String> segments = uri.getPathSegments();
        if (segments.size() < 5) return null;
        String mode = segments.get(2);
        if (!"blob".equals(mode) && !"raw".equals(mode)) return null;
        String repoPath = "/" + segments.get(0) + "/" + segments.get(1) + "/" + join(segments, 3);
        String upstream = uri.buildUpon().scheme("https").authority("raw.githubusercontent.com").encodedPath(repoPath).build().toString();
        String scope = "github:" + segments.get(0) + "/" + segments.get(1);
        return new RawUrl(uri.toString(), upstream, scope, repoPath);
    }

    private static RawUrl gist(Uri uri, String host, String path) {
        if (!"gist.githubusercontent.com".equals(host)) return null;
        List<String> segments = uri.getPathSegments();
        if (segments.size() < 3 || !"raw".equals(segments.get(2))) return null;
        String scope = "gist:" + segments.get(0) + "/" + segments.get(1);
        return new RawUrl(uri.toString(), uri.toString(), scope, path);
    }

    private static RawUrl cnb(Uri uri, String host, String path) {
        RawUrl result = marker(uri, host, path, "/-/git/raw/", "/-/git/raw/", "cnb:");
        if (result != null) return result;
        return marker(uri, host, path, "/-/git/blob/", "/-/git/raw/", "cnb:");
    }

    private static RawUrl dashRaw(Uri uri, String host, String path) {
        String prefix = isKnownGitLab(host) ? "gitlab:" : "git-dash:";
        RawUrl result = marker(uri, host, path, "/-/raw/", "/-/raw/", prefix);
        if (result != null) return result;
        return marker(uri, host, path, "/-/blob/", "/-/raw/", prefix);
    }

    private static RawUrl marker(Uri uri, String host, String path, String marker, String replacement, String prefix) {
        int index = path.indexOf(marker);
        if (index <= 1) return null;
        String project = path.substring(0, index);
        if (segmentCount(project) < 2) return null;
        String upstreamPath = path.substring(0, index) + replacement + path.substring(index + marker.length());
        String upstream = uri.buildUpon().encodedPath(upstreamPath).build().toString();
        return new RawUrl(uri.toString(), upstream, prefix + host + project, upstreamPath);
    }

    private static RawUrl knownSimple(Uri uri, String host, String path) {
        if ("gitee.com".equals(host)) return simple(uri, host, "gitee:", "raw", "blob", "raw");
        if ("bitbucket.org".equals(host)) return simple(uri, host, "bitbucket:", "raw", "src", "raw");
        if (isKnownGitLab(host)) return variableSimple(uri, host, "gitlab:", "raw", "blob", "raw");
        return null;
    }

    private static RawUrl simple(Uri uri, String host, String prefix, String rawMode, String viewMode, String upstreamMode) {
        List<String> segments = uri.getPathSegments();
        if (segments.size() < 5) return null;
        String mode = segments.get(2);
        if (!rawMode.equals(mode) && !viewMode.equals(mode)) return null;
        String upstreamPath = "/" + segments.get(0) + "/" + segments.get(1) + "/" + upstreamMode + "/" + join(segments, 3);
        String upstream = uri.buildUpon().encodedPath(upstreamPath).build().toString();
        String scope = prefix + host + "/" + segments.get(0) + "/" + segments.get(1);
        return new RawUrl(uri.toString(), upstream, scope, upstreamPath);
    }

    private static RawUrl variableSimple(Uri uri, String host, String prefix, String rawMode, String viewMode, String upstreamMode) {
        List<String> segments = uri.getPathSegments();
        for (int i = 2; i < segments.size() - 2; i++) {
            String mode = segments.get(i);
            if (!rawMode.equals(mode) && !viewMode.equals(mode)) continue;
            String project = "/" + join(segments, 0, i);
            String upstreamPath = project + "/" + upstreamMode + "/" + join(segments, i + 1);
            String upstream = uri.buildUpon().encodedPath(upstreamPath).build().toString();
            return new RawUrl(uri.toString(), upstream, prefix + host + project, upstreamPath);
        }
        return null;
    }

    private static RawUrl giteaLike(Uri uri, String host, String path) {
        RawUrl result = giteaMarker(uri, host, path, "/raw/branch/", "/raw/branch/");
        if (result != null) return result;
        result = giteaMarker(uri, host, path, "/raw/tag/", "/raw/tag/");
        if (result != null) return result;
        result = giteaMarker(uri, host, path, "/raw/commit/", "/raw/commit/");
        if (result != null) return result;
        result = giteaMarker(uri, host, path, "/src/branch/", "/raw/branch/");
        if (result != null) return result;
        result = giteaMarker(uri, host, path, "/src/tag/", "/raw/tag/");
        if (result != null) return result;
        return giteaMarker(uri, host, path, "/src/commit/", "/raw/commit/");
    }

    private static RawUrl giteaMarker(Uri uri, String host, String path, String marker, String replacement) {
        int index = path.indexOf(marker);
        if (index <= 1) return null;
        String project = path.substring(0, index);
        if (segmentCount(project) != 2) return null;
        String upstreamPath = path.substring(0, index) + replacement + path.substring(index + marker.length());
        String upstream = uri.buildUpon().encodedPath(upstreamPath).build().toString();
        return new RawUrl(uri.toString(), upstream, "gitea:" + host + project, upstreamPath);
    }

    private static boolean isKnownGitLab(String host) {
        return "gitlab.com".equals(host) || host.startsWith("gitlab.") || host.contains(".gitlab.");
    }

    private static Uri clean(Uri uri) {
        Uri.Builder builder = uri.buildUpon().clearQuery().fragment(null);
        try {
            Set<String> names = uri.getQueryParameterNames();
            for (String name : names) {
                if ("_fm_reload".equals(name) || "_fm_restore".equals(name)) continue;
                for (String value : uri.getQueryParameters(name)) builder.appendQueryParameter(name, value);
            }
        } catch (Throwable ignored) {
            builder.clearQuery();
        }
        return builder.build();
    }

    private static String path(Uri uri) {
        String path = uri.getEncodedPath();
        return TextUtils.isEmpty(path) ? "/" : path;
    }

    private static int segmentCount(String path) {
        if (TextUtils.isEmpty(path) || "/".equals(path)) return 0;
        int count = 0;
        for (String part : path.split("/")) if (!TextUtils.isEmpty(part)) count++;
        return count;
    }

    private static String join(List<String> segments, int start) {
        return join(segments, start, segments.size());
    }

    private static String join(List<String> segments, int start, int end) {
        StringBuilder builder = new StringBuilder();
        for (int i = start; i < end; i++) {
            if (builder.length() > 0) builder.append('/');
            builder.append(Uri.encode(segments.get(i), null));
        }
        return builder.toString();
    }

    private static String trimSlash(String value) {
        return value == null ? "" : value.replaceAll("^/+", "");
    }

    private static String encode(String value) {
        return Uri.encode(value, null);
    }

    private static String encodePath(String value) {
        String[] parts = trimSlash(value).split("/");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (TextUtils.isEmpty(part)) continue;
            if (builder.length() > 0) builder.append('/');
            builder.append(Uri.encode(part, null));
        }
        return builder.toString();
    }

    public static final class RawUrl {
        public final String original;
        public final String upstream;
        public final String scope;
        public final String path;

        RawUrl(String original, String upstream, String scope, String path) {
            this.original = original;
            this.upstream = upstream;
            this.scope = scope;
            this.path = path;
        }

        public boolean sameScope(RawUrl target) {
            return target != null && scope.equals(target.scope);
        }
    }
}
