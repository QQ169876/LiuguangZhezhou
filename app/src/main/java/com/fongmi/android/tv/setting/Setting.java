package com.fongmi.android.tv.setting;
import android.text.TextUtils;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.utils.ResUtil;
import com.github.catvod.utils.Prefers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Setting {

    private static final int MIN_WALL = 0;
    private static final int MAX_WALL = 4;
    private static final int MIN_WALL_TYPE = 0;
    private static final int MAX_WALL_TYPE = 2;
    private static final int MIN_SITE_MODE = 0;
    private static final int MAX_SITE_MODE = 1;
    private static final int MIN_SYNC_MODE = 0;
    private static final int MAX_SYNC_MODE = 2;

    public static String getSwitch(boolean value) {
        return ResUtil.getString(value ? R.string.setting_on : R.string.setting_off);
    }

    public static String getDoh() {
        return Prefers.getString("doh");
    }

    public static void putDoh(String doh) {
        Prefers.put("doh", doh);
    }

    public static String getUa() {
        return Prefers.getString("ua");
    }

    public static void putUa(String ua) {
        Prefers.put("ua", ua);
    }

    public static String getKeyword() {
        return Prefers.getString("keyword");
    }

    public static void putKeyword(String keyword) {
        Prefers.put("keyword", keyword);
    }

    public static String getHot() {
        return Prefers.getString("hot");
    }

    public static void putHot(String hot) {
        Prefers.put("hot", hot);
    }

    public static int getWall() {
        return Math.clamp(Prefers.getInt("wall", 1), MIN_WALL, MAX_WALL);
    }

    public static void putWall(int wall) {
        Prefers.put("wall", Math.clamp(wall, MIN_WALL, MAX_WALL));
    }

    public static int getWallType() {
        return Math.clamp(Prefers.getInt("wall_type", 0), MIN_WALL_TYPE, MAX_WALL_TYPE);
    }

    public static void putWallType(int type) {
        Prefers.put("wall_type", Math.clamp(type, MIN_WALL_TYPE, MAX_WALL_TYPE));
    }

    public static int getThemeColor() {
        return Prefers.getInt("theme_color", -1);
    }

    public static void putThemeColor(int color) {
        Prefers.put("theme_color", color);
    }

    public static int getWallColor() {
        return Prefers.getInt("wall_color", 0);
    }

    public static void putWallColor(int color) {
        Prefers.put("wall_color", color);
    }

    public static int getDynamicColor() {
        int color = getThemeColor();
        if (color == -1) return 0;
        return color != 0 ? color : getWallColor();
    }

    public static int getSiteMode() {
        return Math.clamp(Prefers.getInt("site_mode"), MIN_SITE_MODE, MAX_SITE_MODE);
    }

    public static void putSiteMode(int mode) {
        Prefers.put("site_mode", Math.clamp(mode, MIN_SITE_MODE, MAX_SITE_MODE));
    }

    public static int getSyncMode() {
        return Math.clamp(Prefers.getInt("sync_mode"), MIN_SYNC_MODE, MAX_SYNC_MODE);
    }

    public static void putSyncMode(int mode) {
        Prefers.put("sync_mode", Math.clamp(mode, MIN_SYNC_MODE, MAX_SYNC_MODE));
    }

    public static boolean isIncognito() {
        return Prefers.getBoolean("incognito");
    }

    public static void putIncognito(boolean incognito) {
        Prefers.put("incognito", incognito);
    }

    /**
     * 要不要自动检查更新。默认开。
     *
     * 换了 key：老 key 会被「更新框上点一下取消」直接写成 false，而那一下的本意多半只是
     * 「这版先不装」，结果自动更新就此永久关掉——只有手动去设置里点版本号才再检查一次，
     * 这正是「打开 App 半天没更新提示，非得手动点」的根因。新 key 让所有人（包括已经
     * 被误关的）升级后回到默认开启；真不想被打扰，更新框上有「不再提示」。
     */
    public static boolean getUpdate() {
        return Prefers.getBoolean("update_auto", true);
    }

    public static void putUpdate(boolean update) {
        Prefers.put("update_auto", update);
    }

    /** 被跳过的版本号：点了「取消」的那一版不再反复弹，新版本照常提醒 */
    public static String getUpdateSkip() {
        return Prefers.getString("update_skip");
    }

    public static void putUpdateSkip(String tag) {
        Prefers.put("update_skip", tag == null ? "" : tag);
    }

    public static boolean isRouteAuto() {
        return Prefers.getBoolean("route_auto", true);
    }

    public static void putRouteAuto(boolean auto) {
        Prefers.put("route_auto", auto);
    }

    public static String getRoute() {
        return Prefers.getString("route");
    }

    public static void putRoute(String route) {
        Prefers.put("route", route);
    }

    public static String getSocks() {
        return Prefers.getString("route_socks");
    }

    public static void putSocks(String socks) {
        Prefers.put("route_socks", socks);
    }

    /** 用户自己存过的自定义代理（多条），内置线路不在这里 */
    public static List<String> getRouteCustoms() {
        String value = Prefers.getString("route_customs");
        if (value.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(value.split("\\|")));
    }

    private static void putRouteCustoms(List<String> list) {
        Prefers.put("route_customs", TextUtils.join("|", list));
    }

    public static void addRouteCustom(String socks) {
        if (socks == null || socks.isEmpty()) return;
        List<String> list = getRouteCustoms();
        if (list.contains(socks)) return;
        list.add(socks);
        putRouteCustoms(list);
    }

    public static void removeRouteCustom(String socks) {
        List<String> list = getRouteCustoms();
        if (list.remove(socks)) putRouteCustoms(list);
    }

    /**
     * ⚠️ 这个开关**跟 IPv6 协议没有任何关系**。
     * <p>
     * 「更新线路」里那个按钮显示的文字是「IPv6」，那纯粹是拿来混淆视听的壳子：
     * 它真正干的事只有一件 —— **App 内所有请求（点播、直播、图片、站源视频链接）全部走
     * 用户自己填的那台 SOCKS5 代理**，关掉就恢复各自原本的线路。
     * <p>
     * 存储键还叫 "ipv6"（历史的幌子名）：改键名会让已经偷偷勾上的用户开关失效，所以不动它，
     * 但代码里一律走 {@link #isGlobalProxy()} / {@link #putGlobalProxy(boolean)}，
     * 别再拿这个名字去驱动任何真正的 IPv6 逻辑。
     */
    public static boolean isGlobalProxy() {
        return Prefers.getBoolean("ipv6");
    }

    public static void putGlobalProxy(boolean on) {
        Prefers.put("ipv6", on);
    }

    public static boolean isAdblock() {
        return Prefers.getBoolean("adblock", true);
    }

    public static void putAdblock(boolean adblock) {
        Prefers.put("adblock", adblock);
    }

    public static boolean isZhuyin() {
        return Prefers.getBoolean("zhuyin");
    }

    public static void putZhuyin(boolean zhuyin) {
        Prefers.put("zhuyin", zhuyin);
    }
}
