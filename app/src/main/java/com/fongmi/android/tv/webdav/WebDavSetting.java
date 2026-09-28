package com.fongmi.android.tv.webdav;

import com.github.catvod.utils.Prefers;

import org.json.JSONObject;

import java.util.UUID;

public class WebDavSetting {

    public static final String FILE = "sync.json";

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
        } catch (Exception ignored) {
        }
        return object.toString();
    }

    public static boolean isValid() {
        return !getUrl().isEmpty();
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

    /** 同步目标 = 同步文件地址 + 账号；换地址、换目录或换账号都算换了一份数据源 */
    public static String getScope() {
        return getFileUrl().concat("|").concat(getUser());
    }

    private static String getConfirm() {
        return Prefers.getString("webdav_confirm");
    }

    /** 目标换过之后还没选方向：true 表示需要先问一次，不能直接双向同步 */
    public static boolean isSwitch() {
        return !getConfirm().equals(getScope());
    }

    public static void putConfirm() {
        Prefers.put("webdav_confirm", getScope());
    }
}
