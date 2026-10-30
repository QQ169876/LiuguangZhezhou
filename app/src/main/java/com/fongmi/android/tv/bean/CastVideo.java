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
     * 第三方 DLNA 设备用的地址：没必要绕一圈的时候就别绕。
     * 只有在确实要带 Referer / UA 这类头时，才换成手机本机的局域网直链，
     * 由本机代取再转给大屏——否则直接把源地址给过去，少一次转发。
     */
    public String castUrl() {
        if (headers.isEmpty() || !url.startsWith("http")) return url;
        return relayUrl();
    }

    /**
     * 多机同步（本项目自己的广播）用的地址：**一律**走本机中转。
     *
     * 为什么不看有没有头就决定：局域网里别的设备（电视、盒子）能不能拉到这个源，
     * 事先说不准 —— 有的源在别的内核上敢 direct 失败，有的则是 DRM / 重定向差异。
     * 走中转保证了「发送端拉得到，接收端就一定播得了」，代价是发送端多一份上行。
     */
    public String relayUrl() {
        if (!url.startsWith("http")) return url;
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
