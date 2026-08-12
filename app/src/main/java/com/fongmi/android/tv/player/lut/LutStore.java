package com.fongmi.android.tv.player.lut;

import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.utils.Prefers;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class LutStore {

    private static final String ASSET_DIR = "lut_presets";
    private static final String KEY_USER_TREE = "lut_user_tree";
    private static final Object LOCK = new Object();
    private static List<LutPreset> presets;
    private static int scanSeq;

    public interface PresetCallback {
        void onPresets(List<LutPreset> presets);
    }

    public static List<LutPreset> getPresets() {
        synchronized (LOCK) {
            if (presets == null) presets = scanPresets();
            return new ArrayList<>(presets);
        }
    }

    public static List<LutPreset> getCachedPresets() {
        synchronized (LOCK) {
            return presets == null ? Collections.emptyList() : new ArrayList<>(presets);
        }
    }

    public static boolean hasCache() {
        synchronized (LOCK) {
            return presets != null;
        }
    }

    public static List<LutPreset> refreshPresets() {
        synchronized (LOCK) {
            scanSeq++;
            presets = scanPresets();
            return new ArrayList<>(presets);
        }
    }

    public static void refreshPresetsAsync(PresetCallback callback) {
        int seq;
        synchronized (LOCK) {
            seq = ++scanSeq;
        }
        Task.execute(() -> {
            List<LutPreset> scanned = scanPresets();
            List<LutPreset> result;
            synchronized (LOCK) {
                if (seq != scanSeq) return;
                presets = scanned;
                result = new ArrayList<>(presets);
            }
            if (callback != null) App.post(() -> callback.onPresets(result));
        });
    }

    public static LutPreset find(String id) {
        if (TextUtils.isEmpty(id)) return null;
        for (LutPreset preset : getPresets()) if (id.equals(preset.getId())) return preset;
        return null;
    }

    public static LutPreset getSelectedPreset() {
        return LutSetting.isEnabled() ? find(LutSetting.getPresetId()) : null;
    }

    public static String getSelectedName() {
        LutPreset preset = getSelectedPreset();
        return preset == null ? "" : preset.getName();
    }

    public static String getSelectedShortName() {
        LutPreset preset = getSelectedPreset();
        return preset == null ? "" : preset.getShortName();
    }

    public static void clearCache() {
        synchronized (LOCK) {
            scanSeq++;
            presets = null;
        }
    }

    public static void resortCache() {
        synchronized (LOCK) {
            if (presets != null) sortPresets(presets);
        }
    }

    public static boolean hasUserDir() {
        return !TextUtils.isEmpty(getUserTree());
    }

    public static void setUserDir(Uri uri, int flags) {
        if (uri == null) return;
        if (!ContentResolver.SCHEME_FILE.equalsIgnoreCase(uri.getScheme())) {
            int mode = flags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if (mode == 0) mode = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            try {
                App.get().getContentResolver().takePersistableUriPermission(uri, mode);
            } catch (Throwable ignored) {
            }
        }
        Prefers.put(KEY_USER_TREE, uri.toString());
        clearCache();
    }

    private static String getUserTree() {
        return Prefers.getString(KEY_USER_TREE);
    }

    private static Uri getUserTreeUri() {
        String uri = getUserTree();
        return TextUtils.isEmpty(uri) ? null : Uri.parse(uri);
    }

    private static List<LutPreset> scanPresets() {
        List<LutPreset> items = new ArrayList<>();
        scanAssets(ASSET_DIR, items);
        scanTree(items);
        sortPresets(items);
        return items;
    }

    private static void sortPresets(List<LutPreset> items) {
        Set<String> favorites = LutSetting.favoriteIds();
        Collections.sort(items, (left, right) -> {
            int favorite = Boolean.compare(favorites.contains(right.getId()), favorites.contains(left.getId()));
            if (favorite != 0) return favorite;
            return String.CASE_INSENSITIVE_ORDER.compare(left.getName(), right.getName());
        });
    }

    private static void scanAssets(String dir, List<LutPreset> items) {
        String[] children;
        try {
            children = App.get().getAssets().list(dir);
        } catch (IOException e) {
            return;
        }
        if (children == null) return;
        for (String child : children) {
            String path = dir + "/" + child;
            LutPreset.Format format = LutPreset.formatOf(child);
            if (format != null) {
                items.add(new LutPreset("asset:" + path, displayName(child), path, format, true));
            } else {
                scanAssets(path, items);
            }
        }
    }

    private static void scanTree(List<LutPreset> items) {
        Uri tree = getUserTreeUri();
        if (tree == null) return;
        if (ContentResolver.SCHEME_FILE.equalsIgnoreCase(tree.getScheme())) {
            scanFiles(new File(tree.getPath()), items);
            return;
        }
        try {
            scanTree(tree, DocumentsContract.getTreeDocumentId(tree), items);
        } catch (Exception ignored) {
        }
    }

    private static void scanFiles(File dir, List<LutPreset> items) {
        File[] files = dir == null ? null : dir.listFiles(file -> file.isDirectory() || LutPreset.formatOf(file.getName()) != null);
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                scanFiles(file, items);
                continue;
            }
            LutPreset.Format format = LutPreset.formatOf(file.getName());
            if (format == null) continue;
            items.add(new LutPreset("file:" + file.getAbsolutePath(), displayName(file.getName()), file.getAbsolutePath(), format, false));
        }
    }

    private static void scanTree(Uri tree, String documentId, List<LutPreset> items) {
        ContentResolver resolver = App.get().getContentResolver();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId);
        String[] projection = {Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE};
        try (Cursor cursor = resolver.query(children, projection, null, null, null)) {
            if (cursor == null) return;
            while (cursor.moveToNext()) {
                String childId = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                if (TextUtils.isEmpty(childId) || TextUtils.isEmpty(name)) continue;
                if (Document.MIME_TYPE_DIR.equals(mime)) {
                    scanTree(tree, childId, items);
                    continue;
                }
                LutPreset.Format format = LutPreset.formatOf(name);
                if (format == null) continue;
                Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, childId);
                items.add(new LutPreset("uri:" + uri, displayName(name), uri.toString(), format, false));
            }
        } catch (Exception ignored) {
        }
    }

    public static LutPreset importFile(String path) throws IOException {
        if (TextUtils.isEmpty(path)) throw new IOException("Empty LUT path");
        Uri tree = getUserTreeUri();
        if (tree == null) throw new IOException("LUT directory not selected");
        File source = new File(path);
        LutPreset.Format format = LutPreset.formatOf(source.getName());
        if (format == null) throw new IOException("Unsupported LUT format");
        if (ContentResolver.SCHEME_FILE.equalsIgnoreCase(tree.getScheme())) return importFile(source, new File(tree.getPath()), format);
        Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        String name = uniqueName(root, source.getName());
        Uri target = DocumentsContract.createDocument(App.get().getContentResolver(), root, mimeType(name, format), name);
        if (target == null) throw new IOException("Unable to create LUT file");
        try {
            try (InputStream input = new FileInputStream(source); OutputStream output = App.get().getContentResolver().openOutputStream(target)) {
                if (output == null) throw new IOException("Unable to open LUT target");
                copy(input, output);
            }
        } catch (IOException e) {
            DocumentsContract.deleteDocument(App.get().getContentResolver(), target);
            throw e;
        }
        LutPreset preset = new LutPreset("uri:" + target, displayName(name), target.toString(), format, false);
        try {
            LutEffectFactory.validate(preset);
        } catch (IOException e) {
            DocumentsContract.deleteDocument(App.get().getContentResolver(), target);
            throw e;
        }
        clearCache();
        return preset;
    }

    private static LutPreset importFile(File source, File dir, LutPreset.Format format) throws IOException {
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Unable to create LUT directory");
        if (sameDir(source.getParentFile(), dir)) {
            LutPreset preset = new LutPreset("file:" + source.getAbsolutePath(), displayName(source.getName()), source.getAbsolutePath(), format, false);
            LutEffectFactory.validate(preset);
            clearCache();
            return preset;
        }
        File target = uniqueFile(dir, source.getName());
        try (InputStream input = new FileInputStream(source); OutputStream output = new FileOutputStream(target)) {
            copy(input, output);
        }
        LutPreset preset = new LutPreset("file:" + target.getAbsolutePath(), displayName(target.getName()), target.getAbsolutePath(), format, false);
        try {
            LutEffectFactory.validate(preset);
        } catch (IOException e) {
            target.delete();
            throw e;
        }
        clearCache();
        return preset;
    }

    private static boolean sameDir(File source, File target) {
        if (source == null || target == null) return false;
        try {
            return source.getCanonicalFile().equals(target.getCanonicalFile());
        } catch (IOException e) {
            return source.getAbsolutePath().equals(target.getAbsolutePath());
        }
    }

    private static File uniqueFile(File dir, String name) {
        String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        int dot = safe.lastIndexOf('.');
        String base = dot > 0 ? safe.substring(0, dot) : safe;
        String ext = dot > 0 ? safe.substring(dot) : "";
        File file = new File(dir, safe);
        int index = 1;
        while (file.exists()) file = new File(dir, base + "_" + index++ + ext);
        return file;
    }

    private static String uniqueName(Uri dir, String name) {
        Set<String> names = existingNames(dir);
        String safe = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        int dot = safe.lastIndexOf('.');
        String base = dot > 0 ? safe.substring(0, dot) : safe;
        String ext = dot > 0 ? safe.substring(dot) : "";
        String candidate = safe;
        int index = 1;
        while (names.contains(candidate)) candidate = base + "_" + index++ + ext;
        return candidate;
    }

    private static Set<String> existingNames(Uri dir) {
        Set<String> names = new HashSet<>();
        String documentId = DocumentsContract.getDocumentId(dir);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(dir, documentId);
        String[] projection = {Document.COLUMN_DISPLAY_NAME};
        try (Cursor cursor = App.get().getContentResolver().query(children, projection, null, null, null)) {
            if (cursor == null) return names;
            while (cursor.moveToNext()) names.add(cursor.getString(0));
        } catch (Exception ignored) {
        }
        return names;
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
    }

    private static String mimeType(String name, LutPreset.Format format) {
        if (format == LutPreset.Format.CUBE) return "text/plain";
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }

    private static String displayName(String file) {
        int dot = file.lastIndexOf('.');
        String name = dot > 0 ? file.substring(0, dot) : file;
        name = name.replace('_', ' ').replace('-', ' ').trim();
        if (TextUtils.isEmpty(name)) return file;
        String[] words = name.split("\\s+");
        StringBuilder builder = new StringBuilder();
        for (String word : words) {
            if (TextUtils.isEmpty(word)) continue;
            if (builder.length() > 0) builder.append(' ');
            if (word.length() == 1) builder.append(word.toUpperCase(Locale.ROOT));
            else builder.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1));
        }
        return builder.length() == 0 ? file : builder.toString();
    }
}
