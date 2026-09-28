package com.fongmi.android.tv.server.process;

import android.util.Base64;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.impl.Process;
import com.github.catvod.net.OkHttp;
import com.google.gson.reflect.TypeToken;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Headers;
import okhttp3.Request;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.IStatus;
import fi.iki.elonen.NanoHTTPD.Response.Status;

/**
 * 投屏用的局域网直链：/cast?url=源地址&h=请求头(base64)。
 * 不少片源必须带 Referer / User-Agent 才能取流，电视、盒子上的播放器不会带，
 * 所以由手机这边代取再原样转给大屏，保证推过去一定能播。
 */
public class Cast implements Process {

    private static final String EMPTY = "empty url";

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.startsWith("/cast");
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        try {
            Map<String, String> params = session.getParms();
            String target = params.get("url");
            if (target == null || target.isEmpty()) return Nano.error(EMPTY);
            Map<String, String> headers = decode(params.get("h"));
            String range = session.getHeaders().get("range");
            if (range != null && !range.isEmpty()) headers.put("Range", range);
            if (!headers.containsKey("User-Agent")) headers.put("User-Agent", App.get().getString(com.fongmi.android.tv.R.string.app_name));
            return transfer(target, headers);
        } catch (Throwable e) {
            e.printStackTrace();
            return Nano.error(String.valueOf(e.getMessage()));
        }
    }

    private Response transfer(String target, Map<String, String> headers) throws Exception {
        Request request = new Request.Builder().url(target).headers(Headers.of(fill(headers))).get().build();
        okhttp3.Response res = OkHttp.client(TimeUnit.SECONDS.toMillis(60)).newCall(request).execute();
        int code = res.code();
        String type = res.header("Content-Type", "video/mp4");
        String length = res.header("Content-Length");
        String range = res.header("Content-Range");
        InputStream stream = res.body() == null ? null : res.body().byteStream();
        if (stream == null) {
            res.close();
            return Nano.error(EMPTY);
        }
        Response response = length != null && range == null
                ? NanoHTTPD.newFixedLengthResponse(status(code), type, stream, Long.parseLong(length))
                : NanoHTTPD.newChunkedResponse(status(code), type, stream);
        response.addHeader("Accept-Ranges", "bytes");
        if (range != null) response.addHeader("Content-Range", range);
        return response;
    }

    /** Headers.of 不接受空值，先过滤一遍 */
    private Map<String, String> fill(Map<String, String> headers) {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private Map<String, String> decode(String text) {
        Map<String, String> headers = new HashMap<>();
        if (text == null || text.isEmpty()) return headers;
        try {
            byte[] data = Base64.decode(text, Base64.URL_SAFE);
            String json = new String(data, StandardCharsets.UTF_8);
            Map<String, String> map = App.gson().fromJson(json, new TypeToken<Map<String, String>>() {
            }.getType());
            if (map != null) headers.putAll(map);
        } catch (Throwable ignored) {
        }
        return headers;
    }

    private IStatus status(int code) {
        Status status = Status.lookup(code);
        return status != null ? status : Status.INTERNAL_ERROR;
    }
}
