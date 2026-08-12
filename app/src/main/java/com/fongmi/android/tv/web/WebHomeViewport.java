package com.fongmi.android.tv.web;

import androidx.core.graphics.Insets;
import androidx.core.view.WindowInsetsCompat;

import com.fongmi.android.tv.App;

import java.util.Locale;

public final class WebHomeViewport {

    public static final WebHomeViewport EMPTY = new WebHomeViewport(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, WebHomeChrome.NORMAL, false);

    private final int safeTop;
    private final int safeRight;
    private final int safeBottom;
    private final int safeLeft;
    private final int safeBottomMax;
    private final int gestureLeft;
    private final int gestureRight;
    private final int gestureBottom;
    private final int statusBarHeight;
    private final int navigationBarHeight;
    private final int keyboardBottom;
    private final String chromeMode;
    private final boolean systemBarsHidden;

    private WebHomeViewport(int safeTop, int safeRight, int safeBottom, int safeLeft, int safeBottomMax, int gestureLeft, int gestureRight, int gestureBottom, int statusBarHeight, int navigationBarHeight, int keyboardBottom, String chromeMode, boolean systemBarsHidden) {
        this.safeTop = safeTop;
        this.safeRight = safeRight;
        this.safeBottom = safeBottom;
        this.safeLeft = safeLeft;
        this.safeBottomMax = safeBottomMax;
        this.gestureLeft = gestureLeft;
        this.gestureRight = gestureRight;
        this.gestureBottom = gestureBottom;
        this.statusBarHeight = statusBarHeight;
        this.navigationBarHeight = navigationBarHeight;
        this.keyboardBottom = keyboardBottom;
        this.chromeMode = chromeMode;
        this.systemBarsHidden = systemBarsHidden;
    }

    public static WebHomeViewport from(WindowInsetsCompat insets, String mode, int safeBottomMax) {
        if (insets == null) return EMPTY.withChrome(mode, WebHomeChrome.hidesSystemBars(mode));
        Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
        Insets cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout());
        Insets gestures = insets.getInsets(WindowInsetsCompat.Type.systemGestures());
        boolean hidden = WebHomeChrome.hidesSystemBars(mode);
        int safeTop = Math.max(hidden ? 0 : bars.top, cutout.top);
        int safeRight = Math.max(hidden ? 0 : bars.right, cutout.right);
        int safeBottom = Math.max(hidden ? 0 : bars.bottom, cutout.bottom);
        int safeLeft = Math.max(hidden ? 0 : bars.left, cutout.left);
        int navigation = Math.max(bars.bottom, Math.max(bars.left, bars.right));
        return new WebHomeViewport(safeTop, safeRight, safeBottom, safeLeft, Math.max(safeBottom, safeBottomMax), gestures.left, gestures.right, gestures.bottom, bars.top, navigation, 0, mode, hidden);
    }

    public static WebHomeViewport fixed(int safeTop, int safeRight, int safeBottom, int safeLeft, String mode) {
        return new WebHomeViewport(safeTop, safeRight, safeBottom, safeLeft, safeBottom, 0, 0, 0, 0, 0, 0, mode, false);
    }

    public WebHomeViewport withChrome(String mode, boolean hidden) {
        return new WebHomeViewport(safeTop, safeRight, safeBottom, safeLeft, safeBottomMax, gestureLeft, gestureRight, gestureBottom, statusBarHeight, navigationBarHeight, keyboardBottom, mode, hidden);
    }

    public int getSafeTop() {
        return safeTop;
    }

    public int getSafeRight() {
        return safeRight;
    }

    public int getSafeBottom() {
        return safeBottom;
    }

    public int getSafeLeft() {
        return safeLeft;
    }

    public String getChromeMode() {
        return chromeMode;
    }

    public boolean isSystemBarsHidden() {
        return systemBarsHidden;
    }

    public String key(float density, int width, int height) {
        return width + ":" + height + ":" + cssPx(density, safeTop) + ":" + cssPx(density, safeRight) + ":" + cssPx(density, safeBottom) + ":" + cssPx(density, safeLeft) + ":" + cssPx(density, safeBottomMax) + ":" + cssPx(density, gestureLeft) + ":" + cssPx(density, gestureRight) + ":" + cssPx(density, gestureBottom) + ":" + cssPx(density, statusBarHeight) + ":" + cssPx(density, navigationBarHeight) + ":" + cssPx(density, keyboardBottom) + ":" + chromeMode + ":" + systemBarsHidden;
    }

    public String json(float density, int widthPx, int heightPx) {
        float width = dp(density, widthPx);
        float height = dp(density, heightPx);
        return "{"
                + "\"width\":" + num(width)
                + ",\"height\":" + num(height)
                + ",\"safeTop\":" + cssPx(density, safeTop)
                + ",\"safeRight\":" + cssPx(density, safeRight)
                + ",\"safeBottom\":" + cssPx(density, safeBottom)
                + ",\"safeLeft\":" + cssPx(density, safeLeft)
                + ",\"safeBottomMax\":" + cssPx(density, safeBottomMax)
                + ",\"gestureLeft\":" + cssPx(density, gestureLeft)
                + ",\"gestureRight\":" + cssPx(density, gestureRight)
                + ",\"gestureBottom\":" + cssPx(density, gestureBottom)
                + ",\"statusBarHeight\":" + cssPx(density, statusBarHeight)
                + ",\"navigationBarHeight\":" + cssPx(density, navigationBarHeight)
                + ",\"keyboardBottom\":" + cssPx(density, keyboardBottom)
                + ",\"chromeMode\":" + App.gson().toJson(chromeMode)
                + ",\"systemBarsHidden\":" + systemBarsHidden
                + "}";
    }

    public String script(float density, int widthPx, int heightPx) {
        String detail = json(density, widthPx, heightPx);
        return "(function(){if(!document||!document.documentElement)return;"
                + "var s=document.documentElement.style;"
                + css("web-width", dp(density, widthPx))
                + css("web-height", dp(density, heightPx))
                + css("safe-top", dp(density, safeTop))
                + css("safe-right", dp(density, safeRight))
                + css("safe-bottom", dp(density, safeBottom))
                + css("safe-left", dp(density, safeLeft))
                + css("safe-bottom-max", dp(density, safeBottomMax))
                + css("gesture-left", dp(density, gestureLeft))
                + css("gesture-right", dp(density, gestureRight))
                + css("gesture-bottom", dp(density, gestureBottom))
                + css("status-bar-height", dp(density, statusBarHeight))
                + css("navigation-bar-height", dp(density, navigationBarHeight))
                + css("keyboard-bottom", dp(density, keyboardBottom))
                + "s.setProperty('--fm-chrome-mode'," + App.gson().toJson(chromeMode) + ");"
                + "s.setProperty('--fm-system-bars-hidden','" + (systemBarsHidden ? "1" : "0") + "');"
                + "window.__fmViewport=" + detail + ";"
                + "window.dispatchEvent(new CustomEvent('fmviewport',{detail:" + detail + "}));"
                + "})();";
    }

    private static String css(String name, float value) {
        return "s.setProperty('--fm-" + name + "','" + num(value) + "px');";
    }

    private static String cssPx(float density, int px) {
        return num(dp(density, px));
    }

    private static float dp(float density, int px) {
        return density <= 0 ? px : px / density;
    }

    private static String num(float value) {
        if (Math.abs(value - Math.round(value)) < 0.01f) return String.valueOf(Math.round(value));
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
