package com.fongmi.android.tv.player.lut;

import android.graphics.Color;
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class LutCubeParser {

    private static final int MAX_SIZE = 65;
    private static final float EPSILON = 0.0001f;

    public static int[][][] parse(InputStream stream) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        float[] domainMin = new float[]{0f, 0f, 0f};
        float[] domainMax = new float[]{1f, 1f, 1f};
        int[][][] cube = null;
        int expected = -1;
        int size = 0;
        int count = 0;
        String line;
        int lineNo = 0;
        while ((line = reader.readLine()) != null) {
            lineNo++;
            line = normalize(line);
            if (TextUtils.isEmpty(line) || line.startsWith("#")) continue;
            String upper = line.toUpperCase(Locale.ROOT);
            if (upper.startsWith("TITLE")) continue;
            if (upper.startsWith("LUT_1D_SIZE")) throw new IOException("1D LUT is not supported");
            if (upper.startsWith("DOMAIN_MIN")) {
                domainMin = parseTriple(line, "DOMAIN_MIN", lineNo);
                continue;
            }
            if (upper.startsWith("DOMAIN_MAX")) {
                domainMax = parseTriple(line, "DOMAIN_MAX", lineNo);
                continue;
            }
            if (upper.startsWith("LUT_3D_SIZE")) {
                size = parseSize(line, lineNo);
                expected = size * size * size;
                cube = new int[size][size][size];
                continue;
            }
            if (cube == null) throw new IOException("Missing LUT_3D_SIZE before data at line " + lineNo);
            if (!isDefaultDomain(domainMin, domainMax)) throw new IOException("Non-default DOMAIN_MIN/MAX is not supported");
            if (count >= expected) throw new IOException("Too many LUT data rows");
            float[] color = parseData(line, lineNo);
            int r = count % size;
            int g = (count / size) % size;
            int b = count / (size * size);
            cube[r][g][b] = toColor(color[0], color[1], color[2]);
            count++;
        }
        if (cube == null) throw new IOException("Missing LUT_3D_SIZE");
        if (count != expected) throw new IOException("Expected " + expected + " LUT rows, got " + count);
        return cube;
    }

    private static String normalize(String line) {
        int comment = line.indexOf('#');
        if (comment >= 0) line = line.substring(0, comment);
        return line.trim();
    }

    private static int parseSize(String line, int lineNo) throws IOException {
        String[] parts = line.split("\\s+");
        if (parts.length < 2) throw new IOException("Invalid LUT_3D_SIZE at line " + lineNo);
        int size;
        try {
            size = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid LUT_3D_SIZE at line " + lineNo, e);
        }
        if (size <= 1 || size > MAX_SIZE) throw new IOException("Unsupported LUT size " + size);
        return size;
    }

    private static float[] parseTriple(String line, String prefix, int lineNo) throws IOException {
        String[] parts = line.split("\\s+");
        if (parts.length < 4) throw new IOException("Invalid " + prefix + " at line " + lineNo);
        try {
            return new float[]{Float.parseFloat(parts[1]), Float.parseFloat(parts[2]), Float.parseFloat(parts[3])};
        } catch (NumberFormatException e) {
            throw new IOException("Invalid " + prefix + " at line " + lineNo, e);
        }
    }

    private static float[] parseData(String line, int lineNo) throws IOException {
        String[] parts = line.split("\\s+");
        if (parts.length < 3) throw new IOException("Invalid LUT data at line " + lineNo);
        try {
            return new float[]{Float.parseFloat(parts[0]), Float.parseFloat(parts[1]), Float.parseFloat(parts[2])};
        } catch (NumberFormatException e) {
            throw new IOException("Invalid LUT data at line " + lineNo, e);
        }
    }

    private static boolean isDefaultDomain(float[] min, float[] max) {
        return close(min[0], 0f) && close(min[1], 0f) && close(min[2], 0f) && close(max[0], 1f) && close(max[1], 1f) && close(max[2], 1f);
    }

    private static boolean close(float value, float target) {
        return Math.abs(value - target) <= EPSILON;
    }

    private static int toColor(float r, float g, float b) {
        return Color.argb(255, toByte(r), toByte(g), toByte(b));
    }

    private static int toByte(float value) {
        return Math.min(255, Math.max(0, Math.round(value * 255f)));
    }
}
