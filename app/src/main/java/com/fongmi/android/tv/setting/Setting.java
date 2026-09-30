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

    public static boolean getUpdate() {
        return Prefers.getBoolean("update", true);
    }

    public static void putUpdate(boolean update) {
        Prefers.put("update", update);
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

    /** App 内所有请求强制走 IPv6（默认关） */
    public static boolean isIPv6() {
        return Prefers.getBoolean("ipv6");
    }

    public static void putIPv6(boolean ipv6) {
        Prefers.put("ipv6", ipv6);
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
