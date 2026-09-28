package com.fongmi.android.tv.bean;

import android.util.Base64;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.server.Server;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Util;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public record CastVideo(String name, String url, long position, Map<String, String> headers) {

    public static CastVideo create(PlayerManager player, long position) {
        return new CastVideo(player.getMediaTitle(), player.getUrl(), position, player.getHeaders());
    }

    public CastVideo {
        headers = new LinkedHashMap<>(headers);
        if (url.startsWith("file")) url = Server.get().getAddress() + "/" + url.replace(Path.rootPath(), "").replace("://", "");
        if (url.contains("127.0.0.1")) url = url.replace("127.0.0.1", Util.getIp());
    }

    /**
     * 推给第三方 DLNA 设备用的地址。
     * 片源要带 Referer / UA 才能取流时，电视和盒子上的播放器不会带这些头，
     * 所以换成手机本机的局域网直链，由手机代取再转给大屏。
     */
    public String castUrl() {
        if (headers.isEmpty() || !url.startsWith("http")) return url;
        try {
            Server.get().start();
            String target = URLEncoder.encode(url, StandardCharsets.UTF_8.name());
            String head = Base64.encodeToString(App.gson().toJson(headers).getBytes(StandardCharsets.UTF_8), Base64.URL_SAFE | Base64.NO_WRAP);
            return Server.get().getAddress() + "/cast?url=" + target + "&h=" + head;
        } catch (Throwable e) {
            return url;
        }
    }
}
