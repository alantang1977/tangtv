package androidx.media3.mpvplayer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class MpvFontConfig {

    private static final String[] SYSTEM_FONT_DIRECTORIES = {
            "/system/fonts",
            "/product/fonts",
            "/system_ext/fonts",
            "/vendor/fonts",
            "/odm/fonts"
    };

    private MpvFontConfig() {
    }

    static void write(File configDir, File cacheDir) throws IOException {
        ensureDirectory(configDir, "MPV configuration directory");
        File fontCacheDir = new File(cacheDir, "fontconfig");
        ensureDirectory(fontCacheDir, "fontconfig cache directory");
        writeIfChanged(new File(configDir, "fonts.conf"),
                build(readableSystemFontDirectories(), fontCacheDir));
    }

    static List<File> readableSystemFontDirectories() {
        List<File> directories = new ArrayList<>();
        for (String path : SYSTEM_FONT_DIRECTORIES) {
            File directory = new File(path);
            if (directory.isDirectory() && directory.canRead()) directories.add(directory);
        }
        return Collections.unmodifiableList(directories);
    }

    static String build(List<File> fontDirectories, File cacheDir) {
        StringBuilder xml = new StringBuilder(768);
        xml.append("<?xml version=\"1.0\"?>\n");
        xml.append("<fontconfig>\n");
        for (File directory : fontDirectories) {
            if (directory == null) continue;
            xml.append("  <dir>").append(escapeXml(directory.getAbsolutePath())).append("</dir>\n");
        }
        xml.append("  <cachedir>").append(escapeXml(cacheDir.getAbsolutePath())).append("</cachedir>\n");
        appendAlias(xml, "sans-serif", "Roboto", "Noto Sans", "Noto Sans CJK SC",
                "Noto Sans CJK TC", "Droid Sans Fallback");
        appendAlias(xml, "serif", "Noto Serif", "Noto Serif CJK SC", "Noto Serif CJK TC");
        appendAlias(xml, "monospace", "Droid Sans Mono", "Noto Sans Mono", "Roboto Mono");
        xml.append("</fontconfig>\n");
        return xml.toString();
    }

    static boolean writeIfChanged(File file, String content) throws IOException {
        byte[] expected = content.getBytes(StandardCharsets.UTF_8);
        if (file.isFile() && contentEquals(file, expected)) return false;

        File parent = file.getParentFile();
        if (parent != null) ensureDirectory(parent, "MPV configuration directory");
        File temporary = new File(parent, file.getName() + ".tmp");
        try {
            try (FileOutputStream output = new FileOutputStream(temporary, false)) {
                output.write(expected);
                output.flush();
                output.getFD().sync();
            }
            if (!temporary.renameTo(file)) throw new IOException("Unable to commit fontconfig configuration");
            return true;
        } finally {
            if (temporary.exists()) temporary.delete();
        }
    }

    private static boolean contentEquals(File file, byte[] expected) throws IOException {
        if (file.length() != expected.length) return false;
        try (InputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream(expected.length)) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            byte[] actual = output.toByteArray();
            if (actual.length != expected.length) return false;
            for (int i = 0; i < actual.length; i++) {
                if (actual[i] != expected[i]) return false;
            }
            return true;
        }
    }

    private static void appendAlias(StringBuilder xml, String family, String... preferred) {
        xml.append("  <alias>\n");
        xml.append("    <family>").append(family).append("</family>\n");
        xml.append("    <prefer>\n");
        for (String name : preferred) {
            xml.append("      <family>").append(name).append("</family>\n");
        }
        xml.append("    </prefer>\n");
        xml.append("  </alias>\n");
    }

    private static String escapeXml(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static void ensureDirectory(File directory, String label) throws IOException {
        if (directory.isDirectory()) return;
        if (!directory.mkdirs() && !directory.isDirectory()) throw new IOException("Unable to create " + label);
    }
}
