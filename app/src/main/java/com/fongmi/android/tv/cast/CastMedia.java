package com.fongmi.android.tv.cast;

import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.player.PlayerManager;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 「正在放的这部片子」——把播放器里的东西按 {@link CastSource} 的口径递出去。
 *
 * 播放器对象每次要用的时候现取（{@code supplier}），不留引用：
 * 换引擎、重建播放器、界面重开这些时候引用存着不动是最容易出事的写法。
 *
 * ⚠️ 每一次取值都包了兜底：播放器这边万一正在拆、还没建好，
 * 广播这边只当「这一项没数据」，绝不能把异常抛出去打断播放。
 */
public final class CastMedia implements CastSource {

    private final Supplier<PlayerManager> supplier;
    private volatile String title = "";
    private volatile String ep = "";
    private volatile boolean alive = true;

    public CastMedia(Supplier<PlayerManager> supplier) {
        this.supplier = supplier;
    }

    public CastMedia title(String value) {
        title = value == null ? "" : value;
        return this;
    }

    public CastMedia ep(String value) {
        ep = value == null ? "" : value;
        return this;
    }

    /** 播放页没了就置 false，广播端自己会收工 */
    public void alive(boolean value) {
        alive = value;
    }

    @Override
    public String getCastTitle() {
        return title;
    }

    @Override
    public String getCastEpisode() {
        return ep;
    }

    @Override
    public String getCastUrl() {
        PlayerManager manager = get();
        return manager == null ? "" : manager.getUrl();
    }

    @Override
    public Map<String, String> getCastHeaders() {
        PlayerManager manager = get();
        if (manager == null) return Collections.emptyMap();
        Map<String, String> headers = manager.getHeaders();
        return headers == null ? Collections.emptyMap() : headers;
    }

    /**
     * 外挂字幕一并带过去，接收端就不用自己去找了。
     *
     * ⚠️ 必须是 http/https 的地址：本机路径（下载下来的 srt）在别的机器上根本不存在，发过去只会让那边报个错。
     */
    @Override
    public Map<String, String> getCastSubs() {
        PlayerManager manager = get();
        if (manager == null) return Collections.emptyMap();
        Map<String, String> result = new LinkedHashMap<>();
        List<Sub> subs = manager.getSubs();
        if (subs == null) return result;
        for (Sub sub : subs) {
            String url = sub == null ? "" : sub.getUrl();
            if (url == null || !url.startsWith("http")) continue;
            String name = sub.getName();
            String key = name == null || name.isEmpty() ? sub.getLang() : name;
            if (key == null || key.isEmpty()) key = sub.getUrl();
            if (!result.containsKey(key)) result.put(key, url);
        }
        return result;
    }

    @Override
    public long getCastPosition() {
        PlayerManager manager = get();
        return manager == null ? 0L : manager.getPosition();
    }

    @Override
    public float getCastSpeed() {
        PlayerManager manager = get();
        if (manager == null) return 1f;
        float speed = manager.getSpeed();
        return speed > 0 ? speed : 1f;
    }

    @Override
    public boolean isCastPlaying() {
        PlayerManager manager = get();
        return manager != null && manager.isPlaying();
    }

    @Override
    public boolean isCastAlive() {
        return alive && get() != null;
    }

    private PlayerManager get() {
        try {
            return supplier == null ? null : supplier.get();
        } catch (Throwable e) {
            return null;
        }
    }
}
