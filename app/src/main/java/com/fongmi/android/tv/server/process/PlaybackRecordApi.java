package com.fongmi.android.tv.server.process;

import android.text.TextUtils;

import com.fongmi.android.tv.playback.PlaybackApi;
import com.fongmi.android.tv.playback.PlaybackFieldPolicy;
import com.fongmi.android.tv.playback.PlaybackRecord;
import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.impl.Process;
import com.github.catvod.crawler.SpiderDebug;
import com.google.gson.JsonObject;

import java.util.Map;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;

public class PlaybackRecordApi implements Process {

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return "/api/playback/current".equals(url) || "/playback/current".equals(url);
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        if (session.getMethod() == NanoHTTPD.Method.OPTIONS) return cors(json(Response.Status.NO_CONTENT, ""), session);
        if (session.getMethod() != NanoHTTPD.Method.GET && session.getMethod() != NanoHTTPD.Method.POST) return cors(error(Response.Status.METHOD_NOT_ALLOWED, 405, "只支持 GET/POST"), session);
        String siteKey = siteKey(session.getParms());
        if (TextUtils.isEmpty(siteKey)) return cors(error(Response.Status.BAD_REQUEST, 400, "siteKey不能为空"), session);
        try {
            PlaybackRecord record = PlaybackApi.current(siteKey);
            if (record == null) return cors(error(Response.Status.NOT_FOUND, 404, "当前无可读播放记录"), session);
            SpiderDebug.log("playback-api", "current site=%s record=%s", siteKey, record.dedupeKey);
            return cors(json(Response.Status.OK, record.toJson(PlaybackFieldPolicy.apiSafe()).toString()), session);
        } catch (Throwable e) {
            SpiderDebug.log("playback-api", e);
            return cors(error(Response.Status.INTERNAL_ERROR, 500, e.getMessage()), session);
        }
    }

    private String siteKey(Map<String, String> params) {
        String value = params.get("siteKey");
        if (TextUtils.isEmpty(value)) value = params.get("site");
        return value == null ? "" : value.trim();
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
        response.addHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        response.addHeader("Access-Control-Allow-Headers", "*");
        response.addHeader("Access-Control-Expose-Headers", "*");
        response.addHeader("Access-Control-Max-Age", "86400");
        response.addHeader("Cache-Control", "no-store");
        return response;
    }
}
