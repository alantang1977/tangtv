package com.fongmi.android.tv.player.lut;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.util.LruCache;

import androidx.media3.common.Effect;
import androidx.media3.effect.ColorLut;
import androidx.media3.effect.SingleColorLut;

import com.fongmi.android.tv.App;
import com.github.catvod.crawler.SpiderDebug;

import java.io.IOException;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;

public class LutEffectFactory {

    private static final int MAX_BITMAP_LUT_SIZE = 65;
    private static final LruCache<String, Object> SOURCE_CACHE = new LruCache<>(8);

    public static List<Effect> create(LutPreset preset, int strength) throws IOException {
        long start = System.currentTimeMillis();
        if (preset == null || strength <= 0) return Collections.emptyList();
        Effect effect = createColorLut(preset, strength);
        if (SpiderDebug.isEnabled()) SpiderDebug.log("lut", "create preset=%s format=%s strength=%d cost=%dms", preset.getId(), preset.getFormat(), strength, System.currentTimeMillis() - start);
        return Collections.singletonList(effect);
    }

    public static List<Effect> createPreview(LutPreset preset, int strength, int previewSeconds) throws IOException {
        long start = System.currentTimeMillis();
        if (preset == null || strength <= 0) return Collections.emptyList();
        Effect effect = new SplitColorLutEffect(createColorLut(preset, strength), previewSeconds);
        if (SpiderDebug.isEnabled()) SpiderDebug.log("lut", "create preview preset=%s format=%s strength=%d seconds=%d cost=%dms", preset.getId(), preset.getFormat(), strength, previewSeconds, System.currentTimeMillis() - start);
        return Collections.singletonList(effect);
    }

    public static void validate(LutPreset preset) throws IOException {
        switch (preset.getFormat()) {
            case CUBE -> loadCube(preset);
            case BITMAP -> loadBitmap(preset);
        }
    }

    private static int[][][] loadCube(LutPreset preset) throws IOException {
        String key = "cube:" + preset.getId();
        Object cached = SOURCE_CACHE.get(key);
        if (cached instanceof int[][][] cube) return cube;
        try (InputStream stream = open(preset)) {
            int[][][] cube = LutCubeParser.parse(stream);
            SOURCE_CACHE.put(key, cube);
            return cube;
        }
    }

    private static Bitmap loadBitmap(LutPreset preset) throws IOException {
        String key = "bitmap:" + preset.getId();
        Object cached = SOURCE_CACHE.get(key);
        if (cached instanceof Bitmap bitmap && !bitmap.isRecycled()) return bitmap;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream stream = open(preset)) {
            BitmapFactory.decodeStream(stream, null, bounds);
        }
        if (!isMedia3BitmapLut(bounds.outWidth, bounds.outHeight)) throw new IOException("Bitmap LUT must be N x N^2, N <= " + MAX_BITMAP_LUT_SIZE);
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap;
        try (InputStream stream = open(preset)) {
            bitmap = BitmapFactory.decodeStream(stream, null, options);
        }
        if (bitmap == null) throw new IOException("Unable to decode bitmap LUT");
        if (bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
            Bitmap copy = bitmap.copy(Bitmap.Config.ARGB_8888, false);
            if (copy == null) throw new IOException("Unable to convert bitmap LUT");
            bitmap = copy;
        }
        if (!isMedia3BitmapLut(bitmap.getWidth(), bitmap.getHeight())) throw new IOException("Invalid bitmap LUT size");
        SOURCE_CACHE.put(key, bitmap);
        return bitmap;
    }

    private static InputStream open(LutPreset preset) throws IOException {
        if (preset.isAsset()) return App.get().getAssets().open(preset.getPath());
        if (preset.getPath().startsWith("content://")) {
            InputStream stream = App.get().getContentResolver().openInputStream(Uri.parse(preset.getPath()));
            if (stream == null) throw new IOException("Unable to open LUT uri");
            return stream;
        }
        return new FileInputStream(preset.getPath());
    }

    private static boolean isMedia3BitmapLut(int width, int height) {
        return width > 1 && width <= MAX_BITMAP_LUT_SIZE && height == width * width;
    }

    public static ColorLut createColorLut(LutPreset preset, int strength) throws IOException {
        return switch (preset.getFormat()) {
            case CUBE -> SingleColorLut.createFromCube(mixCube(loadCube(preset), strength));
            case BITMAP -> SingleColorLut.createFromBitmap(mixBitmap(loadBitmap(preset), strength));
        };
    }

    private static int[][][] mixCube(int[][][] source, int strength) {
        int size = source.length;
        if (strength >= 100) return source;
        int[][][] mixed = new int[size][size][size];
        for (int r = 0; r < size; r++) {
            for (int g = 0; g < size; g++) {
                for (int b = 0; b < size; b++) {
                    mixed[r][g][b] = mixColor(identityColor(r, g, b, size), source[r][g][b], strength);
                }
            }
        }
        return mixed;
    }

    private static Bitmap mixBitmap(Bitmap source, int strength) {
        if (strength >= 100) return source;
        int size = source.getWidth();
        Bitmap mixed = Bitmap.createBitmap(size, source.getHeight(), Bitmap.Config.ARGB_8888);
        for (int y = 0; y < source.getHeight(); y++) {
            int r = y / size;
            int g = y % size;
            for (int b = 0; b < size; b++) {
                mixed.setPixel(b, y, mixColor(identityColor(r, g, b, size), source.getPixel(b, y), strength));
            }
        }
        return mixed;
    }

    private static int identityColor(int r, int g, int b, int size) {
        return Color.argb(255, scale(r, size), scale(g, size), scale(b, size));
    }

    private static int scale(int index, int size) {
        return Math.round(index * 255f / (size - 1));
    }

    private static int mixColor(int identity, int color, int strength) {
        float ratio = strength / 100f;
        return Color.argb(255, mix(Color.red(identity), Color.red(color), ratio), mix(Color.green(identity), Color.green(color), ratio), mix(Color.blue(identity), Color.blue(color), ratio));
    }

    private static int mix(int identity, int color, float ratio) {
        return Math.min(255, Math.max(0, Math.round(identity + (color - identity) * ratio)));
    }
}
