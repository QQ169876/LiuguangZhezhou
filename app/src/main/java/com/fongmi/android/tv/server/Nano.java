package com.fongmi.android.tv.server;

import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.bean.Device;
import com.fongmi.android.tv.moontv.MoonSetting;
import com.fongmi.android.tv.music.MusicSetting;
import com.fongmi.android.tv.server.impl.Process;
import com.fongmi.android.tv.server.process.Action;
import com.fongmi.android.tv.server.process.Cast;
import com.fongmi.android.tv.server.process.Cache;
import com.fongmi.android.tv.server.process.Local;
import com.fongmi.android.tv.server.process.Media;
import com.fongmi.android.tv.server.process.Parse;
import com.fongmi.android.tv.server.process.Proxy;
import com.fongmi.android.tv.webdav.WebDavSetting;
import com.github.catvod.utils.Asset;

import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public class Nano extends NanoHTTPD {

    private static final String INDEX = "index.html";

    private List<Process> process;

    public Nano(int port) {
        super(port);
        // 推送文件时要在接收端显示进度：得从写临时文件那一刻起数收到的字节
        setTempFileManagerFactory(UploadProgress.factory(getTempFileManagerFactory()));
        addProcess();
    }

    private void addProcess() {
        process = new ArrayList<>();
        process.add(new Action());
        process.add(new Cast());
        process.add(new Cache());
        process.add(new Local());
        process.add(new Media());
        process.add(new Parse());
        process.add(new Proxy());
    }

    public static Response ok() {
        return ok("OK");
    }

    public static Response ok(String text) {
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, text);
    }

    public static Response error(String text) {
        return error(Response.Status.INTERNAL_ERROR, text);
    }

    public static Response error(Response.Status status, String text) {
        return newFixedLengthResponse(status, MIME_PLAINTEXT, text);
    }

    @Override
    public Response serve(IHTTPSession session) {
        String url = session.getUri().trim();
        Map<String, String> files = new HashMap<>();
        boolean upload = session.getMethod() == Method.POST && url.startsWith("/upload");
        if (upload) UploadProgress.begin(name(session), size(session));
        try {
            if (session.getMethod() == Method.POST) parse(session, files);
            return reply(session, url, files);
        } finally {
            if (upload) UploadProgress.end();
        }
    }

    /** 有别于 /action 那些小请求：/upload 传的是文件，接收端要把进度显示出来 */
    private long size(IHTTPSession session) {
        try {
            return Long.parseLong(session.getHeaders().get("content-length"));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 发送端把文件名放在网址后面（?name=xxx.apk）。
     *
     * 注意不能拿 session.getUri() 去解析 —— 它只给路径，问号后面那截是拿不到的
     * （所以进度框上以前一直不显示文件名）。问号后的部分得问 session.getQueryParameterString()。
     */
    private String name(IHTTPSession session) {
        try {
            String query = session.getQueryParameterString();
            if (query == null) return "";
            for (String pair : query.split("&")) {
                int index = pair.indexOf('=');
                if (index < 0) continue;
                if (!pair.substring(0, index).trim().equals("name")) continue;
                String value = pair.substring(index + 1).trim();
                return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private Response reply(IHTTPSession session, String url, Map<String, String> files) {
        if (url.startsWith("/tvbus")) return ok(LiveConfig.getResp());
        if (url.startsWith("/device")) return ok(Device.get().toString());
        if (url.startsWith("/webdav")) return ok(WebDavSetting.toJson());
        if (url.startsWith("/moontv")) return ok(MoonSetting.toJson());
        if (url.startsWith("/music")) return ok(MusicSetting.toJson());
        for (Process process : process) if (process.isRequest(session, url)) return process.doResponse(session, url, files);
        return getAssets(url.substring(1));
    }

    private void parse(IHTTPSession session, Map<String, String> files) {
        try {
            String ct = session.getHeaders().get("content-type");
            if (ct != null) session.getHeaders().put("content-type", ct.replace("multipart/form-data", "multipart/form-data; charset=utf-8"));
            session.parseBody(files);
        } catch (Exception ignored) {
        }
    }

    private Response getAssets(String path) {
        try {
            if (path.isEmpty()) path = INDEX;
            InputStream is = Asset.open(path);
            return newFixedLengthResponse(Response.Status.OK, getMimeTypeForFile(path), is, -1);
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_HTML, null, 0);
        }
    }
}
