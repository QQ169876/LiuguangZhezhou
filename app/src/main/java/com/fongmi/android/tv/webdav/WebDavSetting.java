package com.fongmi.android.tv.webdav;

import com.fongmi.android.tv.sync.Owner;
import com.fongmi.android.tv.sync.SyncGuard;
import com.github.catvod.utils.Prefers;

import org.json.JSONObject;

import java.util.UUID;

public class WebDavSetting {

    public static final String FILE = "sync.json";

    /** 密钥登录：坚果云这类，账号填邮箱、密码填「应用密钥」（不是登录密码） */
    public static final int AUTH_KEY = 0;
    /** 用户名密码登录：自建 WebDAV（CloudDrive2 / AList / 群晖 / Apache 等） */
    public static final int AUTH_PASSWORD = 1;
    /** 无需登录：服务没开鉴权，请求不带 Authorization */
    public static final int AUTH_NONE = 2;

    /**
     * 登录方式。老版本没有这个键，默认按「密钥登录」处理——升级上来的人
     * 用户名和密码都还在，行为跟以前一模一样，不会突然连不上。
     */
    public static int getAuthMode() {
        int mode = Prefers.getInt("webdav_auth_mode", AUTH_KEY);
        return mode == AUTH_PASSWORD || mode == AUTH_NONE ? mode : AUTH_KEY;
    }

    public static void putAuthMode(int mode) {
        Prefers.put("webdav_auth_mode", mode == AUTH_PASSWORD || mode == AUTH_NONE ? mode : AUTH_KEY);
    }

    public static String getUrl() {
        return Prefers.getString("webdav_url").trim();
    }

    public static void putUrl(String url) {
        Prefers.put("webdav_url", url == null ? "" : url.trim());
    }

    public static String getUser() {
        return Prefers.getString("webdav_user").trim();
    }

    public static void putUser(String user) {
        Prefers.put("webdav_user", user == null ? "" : user.trim());
    }

    public static String getPass() {
        return Prefers.getString("webdav_pass").trim();
    }

    public static void putPass(String pass) {
        Prefers.put("webdav_pass", pass == null ? "" : pass.trim());
    }

    public static String getFolder() {
        String folder = Prefers.getString("webdav_folder").trim();
        return folder.isEmpty() ? "okmove" : folder;
    }

    public static void putFolder(String folder) {
        Prefers.put("webdav_folder", folder == null ? "" : folder.trim());
    }

    public static boolean isEnabled() {
        return Prefers.getBoolean("webdav_enable");
    }

    public static void putEnabled(boolean enable) {
        Prefers.put("webdav_enable", enable);
    }

    public static boolean isAuto() {
        return Prefers.getBoolean("webdav_auto", true);
    }

    public static void putAuto(boolean auto) {
        Prefers.put("webdav_auto", auto);
    }

    /** 是否把扫码登录后留下的 Cookie 也一起同步（默认开） */
    public static boolean isCookie() {
        return Prefers.getBoolean("webdav_cookie", true);
    }

    public static void putCookie(boolean cookie) {
        Prefers.put("webdav_cookie", cookie);
    }

    public static long getLast() {
        return Prefers.getLong("webdav_last");
    }

    public static void putLast(long time) {
        Prefers.put("webdav_last", time);
    }

    public static String getDevice() {
        String device = Prefers.getString("webdav_device");
        if (device.isEmpty()) putDevice(device = UUID.randomUUID().toString().substring(0, 8));
        return device;
    }

    public static void putDevice(String device) {
        Prefers.put("webdav_device", device);
    }

    public static String toJson() {
        JSONObject object = new JSONObject();
        try {
            object.put("url", getUrl());
            object.put("user", getUser());
            object.put("pass", getPass());
            object.put("folder", getFolder());
            object.put("authMode", getAuthMode());
        } catch (Exception ignored) {
        }
        return object.toString();
    }

    public static boolean isValid() {
        return !getUrl().isEmpty();
    }

    /**
     * 要不要带认证头。选了「无需登录」就一律不带；其余两种都走 Basic，
     * 服务器若要 Digest，由 WebDav 里的 Authenticator 收到挑战后自动补上。
     * 账号密码都没填时也当作不需要鉴权，免得给匿名服务发个空 Basic 头被拒。
     */
    public static boolean hasAuth() {
        if (getAuthMode() == AUTH_NONE) return false;
        return !getUser().isEmpty() || !getPass().isEmpty();
    }

    public static boolean isSyncable() {
        return isEnabled() && isValid();
    }

    private static String getBase() {
        String url = getUrl();
        if (url.isEmpty()) return "";
        return url.endsWith("/") ? url : url.concat("/");
    }

    public static String getFolderUrl() {
        StringBuilder builder = new StringBuilder(getBase());
        for (String part : getFolder().split("/")) {
            if (part.isEmpty()) continue;
            builder.append(part).append("/");
        }
        return builder.toString();
    }

    public static String getFileUrl() {
        return getFolderUrl().concat(FILE);
    }

    /**
     * 同步目标 = 同步文件地址 + 账号（+ 非默认的登录方式）。
     * 换登录方式多半意味着换了服务器/换了账号，得重新问一次以哪边为准，别闷头把两边数据混了；
     * 但默认的「密钥登录」不加后缀——老版本存的就是这个格式，升级上来的人不该莫名被问一次。
     */
    public static String getScope() {
        String scope = getFileUrl().concat("|").concat(getUser());
        int mode = getAuthMode();
        return mode == AUTH_KEY ? scope : scope.concat("|").concat(String.valueOf(mode));
    }

    private static String getConfirm() {
        return Prefers.getString("webdav_confirm");
    }

    /** 目标换过之后还没选方向：true 表示需要先问一次，不能直接双向同步 */
    public static boolean isSwitch() {
        return !getConfirm().equals(getScope());
    }

    /**
     * 选完「以哪边为准」，把当前目标记成已确认。若这次确实是**换过同步账号 / 同步标识**，
     * 顺手给批量删除守卫打个招呼：接下来云端覆盖本机造成的整批数据变化是换号的正常结果，
     * 别再弹「是你在 App 里删的吗」（马先生 2026-11-12）。没换目标时 isSwitch() 为 false，不打扰。
     */
    public static void putConfirm() {
        if (isSwitch()) SyncGuard.onAccountChanged(Owner.DAV, getScope());
        Prefers.put("webdav_confirm", getScope());
    }
}
