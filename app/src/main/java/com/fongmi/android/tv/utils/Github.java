package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.net.OkHttp;

import org.json.JSONObject;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class Github {

    /** 线路（通道）名：跟 gitee:// / self:// 并列，是第三条兜底通道 */
    public static final String ROUTE = "github://";

    public static final String REPO = "QQ169876/LiuguangZhezhou";
    public static final String BRANCH = "webdav-sync";

    private static final String RAW = "https://raw.githubusercontent.com/";
    private static final String RELEASE = "https://github.com/" + REPO + "/releases/download/";
    private static final String API = "https://api.github.com/repos/" + REPO;

    /**
     * 改仓库公开/私有用的令牌，**从 local.properties 里的 gh_token 注入**（BuildConfig.GH_TOKEN）。
     *
     * 为什么不直接写死在源码里：仓库是公开的，GitHub 的 secret scanning 会直接拒掉带令牌的提交
     * （实测：建 blob 就回 422 "Secret detected in content"），而且令牌一旦进了公开仓库等于把仓库送人。
     * local.properties 不入库（*.properties 已被 .gitignore 排除），所以只有本机打出来的包带它。
     * 没配这一项也不影响正常更新，只是「临时开放仓库」那条绝路走不了（{@link #hasToken()} 返回 false）。
     */
    private static final String TOKEN = BuildConfig.GH_TOKEN == null ? "" : BuildConfig.GH_TOKEN.trim();

    private static final int API_TIMEOUT = 15000;

    public static boolean is(String route) {
        return ROUTE.equals(route);
    }

    /** 返回一个「干净」的 GitHub 直链，具体走哪条线路由 GhRoute 决定。 */
    public static String getJson(String name) {
        // 老盒子兼容版走独立的版本文件，避免被主线版本覆盖
        if (BuildConfig.LEGACY) name = name + "-legacy";
        return RAW + REPO + "/" + BRANCH + "/apk/" + name + ".json";
    }

    public static String getApk(String tag, String name) {
        if (BuildConfig.LEGACY) name = name.replaceFirst("-", "-legacy-");
        return RELEASE + tag + "/" + name + ".apk";
    }

    public static boolean hasToken() {
        return TOKEN != null && TOKEN.length() > 8;
    }

    /**
     * 最后一招：把仓库临时转成公开。
     *
     * 为什么要有这一招 —— 仓库是私有的，私有仓库的 raw 和 release 附件匿名取一律 404，
     * 前面那堆加速代理再快也只是把 404 更快地送回来。码云和网盘都彻底取不到的时候，
     * 只能把仓库公开一下，取完立刻调 {@link #restore()} 转回私有。
     *
     * 只有成功转公开才记标记；记了标记就一定得还回去，见 {@link #restore()}。
     */
    public static boolean open() {
        if (!hasToken()) return false;
        Boolean was = isPrivate();
        if (was == null) return false; // 连 API 都够不着，这一招用不上
        if (!was) return true;         // 本来就是公开的：不用翻，也就不用往回翻（别把公开的仓库给转成私有）
        if (!setPrivate(false)) return false;
        Setting.putGithubPublic(true);
        Setting.putRescueAt(System.currentTimeMillis());
        DebugLog.d("Update", "最后一招：仓库已临时转公开");
        return true;
    }

    /** 仓库现在是私有的吗？null = 问不到（API 够不着） */
    private static Boolean isPrivate() {
        for (String route : GhRoute.rescueRoutes()) {
            try (Response res = GhRoute.probe(route, API_TIMEOUT).newCall(new Request.Builder()
                    .url(GhRoute.wrap(route, API))
                    .header("Authorization", "Bearer " + TOKEN)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "oktv-update")
                    .get().build()).execute()) {
                if (!res.isSuccessful() || res.body() == null) continue;
                return new JSONObject(res.body().string()).optBoolean("private");
            } catch (Exception e) {
                DebugLog.d("Update", "问仓库状态走 " + GhRoute.host(route) + " 没成：" + e.getMessage());
            }
        }
        return null;
    }

    /**
     * 转回私有。转成功才清标记 —— 没转成功就让它留着，下次启动（或下一次检查更新）还会再试，
     * 绝不能把仓库晾在公开状态。
     */
    public static boolean restore() {
        if (!Setting.isGithubPublic()) return true;
        if (!hasToken()) return false;
        if (!setPrivate(true)) return false;
        Setting.putGithubPublic(false);
        DebugLog.d("Update", "最后一招收尾：仓库已转回私有");
        return true;
    }

    /**
     * PATCH /repos/{repo} 改 private 字段。
     * 直连先试，连不上再走绝路备路代理（见 {@link GhRoute#rescueRoutes()}）。
     */
    private static boolean setPrivate(boolean value) {
        RequestBody body = RequestBody.create("{\"private\":" + value + "}", MediaType.get("application/json; charset=utf-8"));
        for (String route : GhRoute.rescueRoutes()) {
            try (Response res = GhRoute.probe(route, API_TIMEOUT).newCall(new Request.Builder()
                    .url(GhRoute.wrap(route, API))
                    .header("Authorization", "Bearer " + TOKEN)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "oktv-update")
                    .patch(body).build()).execute()) {
                if (!res.isSuccessful() || res.body() == null) continue;
                return new JSONObject(res.body().string()).optBoolean("private", value) == value;
            } catch (Exception e) {
                DebugLog.d("Update", "改仓库公开/私有走 " + GhRoute.host(route) + " 没成：" + e.getMessage());
            }
        }
        return false;
    }
}
