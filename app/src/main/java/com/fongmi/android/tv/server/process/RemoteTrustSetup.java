package com.fongmi.android.tv.server.process;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.remote.RemoteAgent;
import com.fongmi.android.tv.remote.RemoteModels.ServerCapabilities;
import com.fongmi.android.tv.remote.RemoteModels.RemoteProfile;
import com.fongmi.android.tv.remote.RemoteStore;
import com.fongmi.android.tv.remote.RemoteTokens;
import com.fongmi.android.tv.server.impl.Process;
import com.fongmi.android.tv.ui.dialog.RemoteTrustDialog;
import com.fongmi.android.tv.utils.Notify;
import com.github.catvod.net.OkHttp;

import java.io.IOException;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;

public class RemoteTrustSetup implements Process {

    private static final String PATH = "/remote/trust/setup";

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.equals(PATH);
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        if (session.getMethod() == NanoHTTPD.Method.POST) return save(session.getParms());
        return page("");
    }

    private Response save(Map<String, String> params) {
        String serverUrl = params == null ? "" : params.get("serverUrl");
        String origin = RemoteTokens.normalizeOrigin(serverUrl);
        if (TextUtils.isEmpty(origin)) return page("请输入有效的中转服务 URL", Response.Status.BAD_REQUEST);
        try {
            checkRelay(origin);
        } catch (Throwable e) {
            return page("无法连接中转服务：" + concise(e), Response.Status.SERVICE_UNAVAILABLE);
        }
        RemoteProfile profile = RemoteStore.prepareProfile(serverUrl, true, true);
        profile.enabled = true;
        profile.keepOnline = true;
        RemoteStore.upsertProfile(profile);
        RemoteAgent.get().start();
        RemoteTrustDialog.onRelaySetupSaved(TextUtils.isEmpty(profile.serverUrl) ? profile.serverOrigin : profile.serverUrl);
        App.post(() -> Notify.show("中转服务已更新"));
        return page("已保存：" + origin);
    }

    private Response page(String message) {
        return page(message, Response.Status.OK);
    }

    private Response page(String message, Response.Status status) {
        RemoteProfile profile = RemoteStore.firstProfile();
        String current = profile == null ? "" : (TextUtils.isEmpty(profile.serverUrl) ? profile.serverOrigin : profile.serverUrl);
        Response response = NanoHTTPD.newFixedLengthResponse(status, "text/html; charset=utf-8", html(current, message));
        response.addHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        response.addHeader("Pragma", "no-cache");
        return response;
    }

    private void checkRelay(String origin) throws IOException {
        okhttp3.Request request = new okhttp3.Request.Builder().url(origin + "/api/server/capabilities").get().build();
        try (okhttp3.Response response = OkHttp.client(5000).newCall(request).execute()) {
            okhttp3.ResponseBody body = response.body();
            String text = body == null ? "" : body.string();
            if (!response.isSuccessful()) throw new IOException("HTTP " + response.code() + (TextUtils.isEmpty(text) ? "" : ": " + text));
            ServerCapabilities capabilities = App.gson().fromJson(text, ServerCapabilities.class);
            if (capabilities == null || !capabilities.ok) throw new IOException("capabilities 返回异常");
        }
    }

    private String concise(Throwable e) {
        if (e == null || TextUtils.isEmpty(e.getMessage())) return "网络不可用或服务未响应";
        String message = e.getMessage();
        int line = message.indexOf('\n');
        if (line >= 0) message = message.substring(0, line);
        return message.length() > 120 ? message.substring(0, 120) + "..." : message;
    }

    private String html(String current, String message) {
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,viewport-fit=cover\">"
                + "<title>远程托管中转服务</title><style>"
                + "html,body{margin:0;background:#f4f6f8;color:#202124;font:15px/1.5 -apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif}"
                + "main{box-sizing:border-box;max-width:520px;margin:0 auto;padding:22px 16px}"
                + "h1{margin:0 0 8px;font-size:22px;font-weight:700}.hint{margin:0 0 18px;color:#5f6368}"
                + "form{background:#fff;border:1px solid #dadce0;border-radius:10px;padding:14px;box-shadow:0 2px 12px rgba(60,64,67,.08)}"
                + "label{display:block;margin:0 0 7px;color:#3c4043;font-weight:650}input{box-sizing:border-box;width:100%;min-height:44px;border:1px solid #c8cdd2;border-radius:8px;padding:8px 10px;font:inherit;outline:none}"
                + "input:focus{border-color:#0b57d0;box-shadow:0 0 0 3px rgba(11,87,208,.12)}button{width:100%;margin-top:12px;min-height:42px;border:0;border-radius:8px;background:#0b57d0;color:#fff;font:inherit;font-weight:650}"
                + ".msg{margin:12px 0 0;padding:9px 10px;border-radius:8px;background:#e8f0fe;color:#174ea6;overflow-wrap:anywhere}.current{margin-top:12px;color:#5f6368;font-size:13px;overflow-wrap:anywhere}"
                + "</style></head><body><main><h1>远程托管中转服务</h1>"
                + "<p class=\"hint\">填写中转服务 URL，提交后会保存到当前设备。</p>"
                + "<form method=\"post\" action=\"" + PATH + "\"><label for=\"serverUrl\">中转服务 URL</label>"
                + "<input id=\"serverUrl\" name=\"serverUrl\" type=\"url\" inputmode=\"url\" autocomplete=\"url\" placeholder=\"https://example.workers.dev\" value=\"" + escape(current) + "\" autofocus>"
                + "<button type=\"submit\">保存到此设备</button>"
                + (!TextUtils.isEmpty(message) ? "<div class=\"msg\">" + escape(message) + "</div>" : "")
                + (!TextUtils.isEmpty(current) ? "<div class=\"current\">当前：" + escape(current) + "</div>" : "")
                + "</form></main></body></html>";
    }

    private String escape(String text) {
        if (TextUtils.isEmpty(text)) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
