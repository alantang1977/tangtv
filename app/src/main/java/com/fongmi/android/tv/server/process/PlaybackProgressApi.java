package com.fongmi.android.tv.server.process;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.playback.PlaybackProgressApplyResult;
import com.fongmi.android.tv.playback.PlaybackProgressBatchResult;
import com.fongmi.android.tv.playback.PlaybackProgressDeleteInput;
import com.fongmi.android.tv.playback.PlaybackProgressInput;
import com.fongmi.android.tv.playback.PlaybackProgressWriter;
import com.fongmi.android.tv.playback.ViewingRecordSyncStore;
import com.fongmi.android.tv.server.impl.Process;
import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.crawler.SpiderDebug;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;

public class PlaybackProgressApi implements Process {

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return "/api/playback/progress".equals(url)
                || "/api/playback/progress/batch".equals(url)
                || "/api/playback/progress/delete".equals(url)
                || "/playback/progress".equals(url)
                || "/playback/progress/batch".equals(url)
                || "/playback/progress/delete".equals(url);
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        if (session.getMethod() == NanoHTTPD.Method.OPTIONS) return cors(json(Response.Status.NO_CONTENT, ""), session);
        if (session.getMethod() != NanoHTTPD.Method.POST) return cors(error(Response.Status.METHOD_NOT_ALLOWED, 405, "只支持 POST"), session);
        if (!ViewingRecordSyncStore.isEnabled()) return cors(error(Response.Status.FORBIDDEN, 403, "观影记录同步未开启"), session);
        if (!ViewingRecordSyncStore.isLocalWriteEnabled()) return cors(error(Response.Status.FORBIDDEN, 403, "本机 API 修改未开启"), session);
        if (Setting.isIncognito()) return cors(error(Response.Status.FORBIDDEN, 403, "隐身模式不允许写入"), session);
        try {
            String body = readBody(session, files);
            if (TextUtils.isEmpty(body)) body = session.getParms().get("body");
            if (TextUtils.isEmpty(body)) return cors(error(Response.Status.BAD_REQUEST, 400, "请求体不能为空"), session);
            if (url.endsWith("/delete")) return cors(deleteBatch(body), session);
            if (url.endsWith("/batch")) return cors(writeBatch(body), session);
            return cors(writeOne(body), session);
        } catch (IllegalArgumentException e) {
            return cors(error(Response.Status.BAD_REQUEST, 400, e.getMessage()), session);
        } catch (Throwable e) {
            SpiderDebug.log("playback-progress-api", e);
            return cors(error(Response.Status.INTERNAL_ERROR, 500, e.getMessage()), session);
        }
    }

    private String readBody(IHTTPSession session, Map<String, String> files) throws Exception {
        String body = files.get("postData");
        if (!TextUtils.isEmpty(body)) return body;
        int length = length(session);
        if (length <= 0) return "";
        InputStream input = session.getInputStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream(length);
        byte[] buffer = new byte[Math.min(8192, length)];
        int remaining = length;
        while (remaining > 0) {
            int read = input.read(buffer, 0, Math.min(buffer.length, remaining));
            if (read < 0) break;
            output.write(buffer, 0, read);
            remaining -= read;
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private int length(IHTTPSession session) {
        try {
            String value = session.getHeaders().get("content-length");
            if (TextUtils.isEmpty(value)) value = session.getHeaders().get("Content-Length");
            return TextUtils.isEmpty(value) ? 0 : Integer.parseInt(value);
        } catch (Exception e) {
            return 0;
        }
    }

    private Response writeOne(String body) {
        PlaybackProgressApplyResult result = PlaybackProgressWriter.applyFromLocalApi(PlaybackProgressInput.fromJson(body));
        SpiderDebug.log("playback-progress-api", "single action=%s key=%s success=%s", result.action, result.historyKey, result.success);
        return json(result.success ? Response.Status.OK : Response.Status.BAD_REQUEST, App.gson().toJson(result));
    }

    private Response writeBatch(String body) {
        List<PlaybackProgressInput> inputs = PlaybackProgressInput.listFromJson(body);
        if (inputs.isEmpty()) return error(Response.Status.BAD_REQUEST, 400, "items不能为空");
        PlaybackProgressBatchResult result = PlaybackProgressWriter.applyFromLocalApi(inputs);
        SpiderDebug.log("playback-progress-api", "batch total=%s applied=%s skipped=%s failed=%s", result.total, result.applied, result.skipped, result.failed);
        return json(Response.Status.OK, App.gson().toJson(result));
    }

    private Response deleteBatch(String body) {
        List<PlaybackProgressDeleteInput> inputs = PlaybackProgressDeleteInput.listFromJson(body);
        if (inputs.isEmpty()) return error(Response.Status.BAD_REQUEST, 400, "items不能为空");
        PlaybackProgressBatchResult result = PlaybackProgressWriter.deleteFromLocalApi(inputs);
        SpiderDebug.log("playback-progress-api", "delete total=%s applied=%s deleted=%s skipped=%s failed=%s", result.total, result.applied, result.deleted, result.skipped, result.failed);
        return json(Response.Status.OK, App.gson().toJson(result));
    }

    private Response error(Response.Status status, int code, String message) {
        JsonObject object = new JsonObject();
        object.addProperty("code", code);
        object.addProperty("message", TextUtils.isEmpty(message) ? "请求失败" : message);
        return json(status, object.toString());
    }

    private Response json(Response.Status status, String text) {
        return NanoHTTPD.newFixedLengthResponse(status, "application/json; charset=utf-8", text);
    }

    private Response cors(Response response, IHTTPSession session) {
        String origin = session.getHeaders().get("origin");
        response.addHeader("Access-Control-Allow-Origin", TextUtils.isEmpty(origin) ? "*" : origin);
        response.addHeader("Access-Control-Allow-Credentials", "true");
        response.addHeader("Access-Control-Allow-Methods", "POST,OPTIONS");
        response.addHeader("Access-Control-Allow-Headers", "*");
        response.addHeader("Access-Control-Expose-Headers", "*");
        response.addHeader("Access-Control-Max-Age", "86400");
        response.addHeader("Cache-Control", "no-store");
        return response;
    }
}
