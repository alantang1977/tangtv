package com.fongmi.android.tv.node;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import okhttp3.Response;

/**
 * 猫源 bundle（CatPawOpen 的 {@code index.js}）的下载与缓存。
 *
 * <p>用户填的是 {@code .../index.js.md5}——那个地址返回 32 位校验值，真正的 bundle 在去掉
 * {@code .md5} 后缀的地址上。每次启动只拉几十字节的 md5 比对，命中就用本地缓存，
 * 避免重复下载 1.2MB 的 bundle。
 */
public final class NodeBundle {

    private static final String SUFFIX = ".md5";

    private NodeBundle() {
    }

    /** 用户填 .md5 地址（约定如此），也容忍直接填 bundle 地址。 */
    public static String bundleUrl(String url) {
        if (TextUtils.isEmpty(url)) return "";
        String trimmed = url.trim();
        return trimmed.endsWith(SUFFIX) ? trimmed.substring(0, trimmed.length() - SUFFIX.length()) : trimmed;
    }

    public static String md5Url(String url) {
        if (TextUtils.isEmpty(url)) return "";
        String trimmed = url.trim();
        return trimmed.endsWith(SUFFIX) ? trimmed : trimmed + SUFFIX;
    }

    public static File dir(Context context) {
        File dir = new File(context.getFilesDir(), "node/bundle");
        dir.mkdirs();
        return dir;
    }

    public static File file(Context context) {
        return new File(dir(context), "index.js");
    }

    /**
     * bundle 的配置文件。服务端会在里面自动写好 {@code server.url} 和 {@code authorization}，
     * 且各站点的配置项（如 {@code ffm3u8.url}）都得由它提供——传空对象会让 bundle 在
     * 注册/首个请求阶段抛 undefined。
     */
    public static File config(Context context) {
        return new File(dir(context), "index.config.js");
    }

    private static File stamp(Context context) {
        return new File(dir(context), "index.js.md5");
    }

    private static File configStamp(Context context) {
        return new File(dir(context), "index.config.js.md5");
    }

    private static String configUrl(String url) {
        String bundle = bundleUrl(url);
        int slash = bundle.lastIndexOf('/');
        return slash < 0 ? bundle : bundle.substring(0, slash + 1) + "index.config.js";
    }

    /**
     * 确保本地 bundle 与远端一致。
     *
     * @return null 表示就绪，否则是失败原因
     */
    public static synchronized String ensure(Context context, String url) {
        try {
            File bundle = file(context);
            String remote = remoteMd5(url);
            if (bundle.exists() && bundle.length() > 0) {
                String local = read(stamp(context));
                // 远端拿不到 md5（离线等）时不该阻断，已有缓存就先用着。
                // 但配置仍要单独校验——服务端改夸克 Cookie 这类操作只会变 index.config.js，
                // bundle 的 md5 不变，早退会导致新配置永远拉不到。
                if (TextUtils.isEmpty(remote) || remote.equalsIgnoreCase(local)) return ensureConfig(context, url);
            } else if (TextUtils.isEmpty(remote)) {
                remote = "";
            }
            String error = download(bundleUrl(url), bundle);
            if (error != null) return error;
            String actual = TextUtils.isEmpty(remote) ? Util.md5(bundle) : remote;
            write(stamp(context), actual);
            return ensureConfig(context, url);
        } catch (Exception e) {
            SpiderDebug.log("node", e);
            return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
    }

    /** 配置文件同样带 .md5，按同一套缓存判定。 */
    private static String ensureConfig(Context context, String url) {
        File target = config(context);
        String remote = remoteMd5(configUrl(url));
        if (target.exists() && target.length() > 0) {
            String local = read(configStamp(context));
            if (TextUtils.isEmpty(remote) || remote.equalsIgnoreCase(local)) return null;
        }
        String error = download(configUrl(url), target);
        if (error != null) return error;
        try {
            write(configStamp(context), TextUtils.isEmpty(remote) ? Util.md5(target) : remote);
        } catch (IOException ignored) {
        }
        return null;
    }

    private static String remoteMd5(String url) {
        try {
            String text = OkHttp.string(md5Url(url));
            if (text == null) return "";
            String value = text.trim();
            return value.length() == 32 ? value : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String download(String url, File target) {
        try (Response res = OkHttp.newCall(url, "node-bundle").execute()) {
            if (!res.isSuccessful() || res.body() == null) return "bundle 下载失败 HTTP " + res.code();
            try (InputStream in = res.body().byteStream(); FileOutputStream out = new FileOutputStream(target)) {
                byte[] buf = new byte[65536];
                int len;
                while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
            }
            return target.length() > 0 ? null : "bundle 为空";
        } catch (Exception e) {
            SpiderDebug.log("node", e);
            return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        }
    }

    private static String read(File file) {
        if (!file.exists()) return "";
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] buf = new byte[64];
            int len = in.read(buf);
            return len <= 0 ? "" : new String(buf, 0, len).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static void write(File file, String text) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes());
        }
    }
}
