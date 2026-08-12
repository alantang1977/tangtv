package androidx.media3.mpvplayer;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MpvFontConfigTest {

    @Test
    public void build_includesAllReadableDirectoriesAndEscapesPaths() throws Exception {
        File root = Files.createTempDirectory("mpv-fontconfig-&").toFile();
        File system = new File(root, "system<fonts");
        File product = new File(root, "product-fonts");
        File cache = new File(root, "cache&fonts");

        String config = MpvFontConfig.build(List.of(system, product), cache);

        assertTrue(config.startsWith("<?xml version=\"1.0\"?>\n<fontconfig>\n"));
        assertTrue(config.contains("<dir>" + escaped(system.getAbsolutePath()) + "</dir>"));
        assertTrue(config.contains("<dir>" + escaped(product.getAbsolutePath()) + "</dir>"));
        assertTrue(config.contains("<cachedir>" + escaped(cache.getAbsolutePath()) + "</cachedir>"));
        assertTrue(config.contains("<family>Noto Sans CJK SC</family>"));
        assertTrue(config.endsWith("</fontconfig>\n"));
    }

    @Test
    public void writeIfChanged_doesNotRewriteIdenticalConfiguration() throws Exception {
        File root = Files.createTempDirectory("mpv-fontconfig-write").toFile();
        File output = new File(root, "fonts.conf");

        assertTrue(MpvFontConfig.writeIfChanged(output, "<fontconfig/>\n"));
        long firstModified = output.lastModified();
        Thread.sleep(20);

        assertFalse(MpvFontConfig.writeIfChanged(output, "<fontconfig/>\n"));
        assertTrue(output.isFile());
        assertTrue(Files.readString(output.toPath(), StandardCharsets.UTF_8).equals("<fontconfig/>\n"));
        assertTrue(output.lastModified() == firstModified);
    }

    @Test
    public void writeIfChanged_replacesStaleConfigurationAndCleansTemporaryFile() throws Exception {
        File root = Files.createTempDirectory("mpv-fontconfig-replace").toFile();
        File output = new File(root, "fonts.conf");
        Files.writeString(output.toPath(), "old", StandardCharsets.UTF_8);

        assertTrue(MpvFontConfig.writeIfChanged(output, "new"));

        assertTrue(Files.readString(output.toPath(), StandardCharsets.UTF_8).equals("new"));
        assertFalse(new File(root, "fonts.conf.tmp").exists());
    }

    private static String escaped(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;");
    }
}
