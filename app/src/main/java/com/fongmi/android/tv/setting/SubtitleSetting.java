package com.fongmi.android.tv.setting;

import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.accessibility.CaptioningManager;

import androidx.annotation.Nullable;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.SubtitleView;

import com.fongmi.android.tv.api.config.VodConfig;
import com.github.catvod.utils.Prefers;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SubtitleSetting {

    public static final int STYLE_SOURCE_ORIGINAL = 0;
    public static final int STYLE_SOURCE_SYSTEM = 1;
    public static final int STYLE_SOURCE_CUSTOM = 2;
    public static final int SECONDARY_SUBTITLE_OFF = -2;
    public static final int SECONDARY_SUBTITLE_AUTO = -1;
    public static final float MIN_SCALE = 0.5f;
    public static final float MAX_SCALE = 2.0f;
    public static final float MIN_POSITION = -20.0f;
    public static final float MAX_POSITION = 30.0f;
    public static final float MIN_OPACITY = 0.0f;
    public static final float MAX_OPACITY = 1.0f;
    public static final float MIN_EDGE_WIDTH = 0.0f;
    public static final float MAX_EDGE_WIDTH = 6.0f;
    public static final float MIN_SHADOW = 0.0f;
    public static final float MAX_SHADOW = 8.0f;
    public static final float MIN_SECONDARY_POSITION = 0.0f;
    public static final float MAX_SECONDARY_POSITION = 150.0f;
    public static final int SUBTITLE_COLOR_WHITE = Color.WHITE;
    public static final int SUBTITLE_COLOR_YELLOW = 0xFFFFFF00;
    public static final int SUBTITLE_COLOR_CYAN = 0xFF00E5FF;
    public static final int SUBTITLE_COLOR_GREEN = 0xFF76FF03;
    public static final int SUBTITLE_COLOR_ORANGE = 0xFFFF9800;
    public static final int SUBTITLE_COLOR_PINK = 0xFFFF80AB;
    public static final int SUBTITLE_COLOR_RED = 0xFFFF5252;
    public static final int SUBTITLE_COLOR_BLUE = 0xFF64B5F6;
    public static final int SUBTITLE_COLOR_GRAY = 0xFFBDBDBD;
    public static final int SUBTITLE_COLOR_BLACK = Color.BLACK;
    public static final int SUBTITLE_BACKGROUND_DIM = 0x66000000;
    public static final int SUBTITLE_BACKGROUND_BLACK = 0xCC000000;
    public static final int SUBTITLE_BACKGROUND_GRAY = 0xCC202020;
    private static final int DEFAULT_TEXT_COLOR = SUBTITLE_COLOR_WHITE;
    private static final int DEFAULT_BACKGROUND_COLOR = Color.TRANSPARENT;
    private static final int DEFAULT_EDGE_TYPE = CaptionStyleCompat.EDGE_TYPE_OUTLINE;
    private static final int DEFAULT_EDGE_COLOR = SUBTITLE_COLOR_BLACK;
    private static final float DEFAULT_SCALE = 1.0f;
    private static final float DEFAULT_POSITION = 0.0f;
    private static final float DEFAULT_TEXT_OPACITY = 1.0f;
    private static final float DEFAULT_EDGE_OPACITY = 1.0f;
    private static final float DEFAULT_BACKGROUND_OPACITY = 1.0f;
    private static final float DEFAULT_EDGE_WIDTH = CaptionStyleCompat.DEFAULT_EDGE_WIDTH;
    private static final float DEFAULT_SHADOW = CaptionStyleCompat.DEFAULT_SHADOW_OFFSET;
    private static final int DEFAULT_SECONDARY_TRACK = SECONDARY_SUBTITLE_OFF;
    private static final float DEFAULT_SECONDARY_POSITION = 10.0f;

    /** 在线搜字幕的来源：自动（主源不行就换备用）/ 伪射手 / OpenSubtitles / SubDL */
    public static final int SOURCE_AUTO = 0;
    public static final int SOURCE_ASSRT = 1;
    public static final int SOURCE_OPENSUB = 2;
    public static final int SOURCE_SUBDL = 3;

    public static int getSearchSource() {
        return Math.clamp(Prefers.getInt("subtitle_search_source", SOURCE_AUTO), SOURCE_AUTO, SOURCE_SUBDL);
    }

    public static void putSearchSource(int source) {
        Prefers.put("subtitle_search_source", Math.clamp(source, SOURCE_AUTO, SOURCE_SUBDL));
    }

    /**
     * 内置的兜底凭证：马先生自己注册的，免得 TV 端还得拿遥控器敲这一长串。
     * 用户自己填的优先级更高（齿轮里填了就用填的），这条只是"填了也能用、不填也能用"的保底。
     */
    private static final String BUILTIN_SUBDL_KEY = "subdl_SHN30MlCv3Pdm5giX3tXbgk3Qxp-iIQn87HhLR0iDPk";
    private static final String BUILTIN_ASSRT_TOKEN = "6gWhJbgDSvnjWbbUkuG1VvWMt4SXB8B2";

    public static String getSearchSubdlKey() {
        return Prefers.getString("subtitle_search_subdl_key", "");
    }

    public static void putSearchSubdlKey(String key) {
        Prefers.put("subtitle_search_subdl_key", key == null ? "" : key.trim());
    }

    public static String getEffectiveSubdlKey() {
        String key = getSearchSubdlKey();
        return TextUtils.isEmpty(key) ? BUILTIN_SUBDL_KEY : key;
    }

    public static String getSearchToken() {
        return Prefers.getString("subtitle_search_token", "");
    }

    public static String getEffectiveToken() {
        String userToken = getSearchToken();
        if (!TextUtils.isEmpty(userToken)) return userToken;
        String configToken = VodConfig.get().getConfig().getAssrt();
        return TextUtils.isEmpty(configToken) ? BUILTIN_ASSRT_TOKEN : configToken;
    }

    public static void putSearchToken(String token) {
        Prefers.put("subtitle_search_token", token);
    }

    public static int getStyleSource() {
        int legacy = Prefers.getBoolean("caption") ? STYLE_SOURCE_SYSTEM : STYLE_SOURCE_ORIGINAL;
        return Math.clamp(Prefers.getInt("subtitle_style_source", legacy), STYLE_SOURCE_ORIGINAL, STYLE_SOURCE_CUSTOM);
    }

    public static void putStyleSource(int source) {
        Prefers.put("subtitle_style_source", Math.clamp(source, STYLE_SOURCE_ORIGINAL, STYLE_SOURCE_CUSTOM));
    }

    public static boolean isSystemStyle() {
        return getStyleSource() == STYLE_SOURCE_SYSTEM;
    }

    public static boolean isCustomStyle() {
        return getStyleSource() == STYLE_SOURCE_CUSTOM;
    }

    public static float getScale() {
        float textSize = Prefers.getFloat("subtitle_text_size");
        float legacy = textSize == 0 ? DEFAULT_SCALE : textSize / SubtitleView.DEFAULT_TEXT_SIZE_FRACTION;
        return Math.clamp(Prefers.getFloat("subtitle_scale", legacy), MIN_SCALE, MAX_SCALE);
    }

    public static float getScale(Context context) {
        float scale = getScale();
        if (scale == DEFAULT_SCALE) scale *= getSystemCaptionFontScale(context);
        return scale;
    }

    private static float getSystemCaptionFontScale(Context context) {
        if (!isSystemStyle()) return 1.0f;
        CaptioningManager manager = (CaptioningManager) context.getSystemService(Context.CAPTIONING_SERVICE);
        return manager == null ? 1.0f : manager.getFontScale();
    }

    public static void putScale(float value) {
        Prefers.put("subtitle_scale", Math.clamp(value, MIN_SCALE, MAX_SCALE));
    }

    public static float getPosition() {
        float value = Prefers.getFloat("subtitle_position", DEFAULT_POSITION);
        if (value != 0 && Math.abs(value) < 0.5f) value *= 100.0f;
        return Math.clamp(value, MIN_POSITION, MAX_POSITION);
    }

    public static void putPosition(float value) {
        Prefers.put("subtitle_position", Math.clamp(value, MIN_POSITION, MAX_POSITION));
    }

    public static int getTextBaseColor() {
        return Prefers.getInt("subtitle_foreground_color", DEFAULT_TEXT_COLOR);
    }

    public static void putTextColor(int color) {
        Prefers.put("subtitle_foreground_color", color);
    }

    public static int getTextColor() {
        return applyOpacity(getTextBaseColor(), getTextOpacity());
    }

    public static float getTextOpacity() {
        return getOpacity("subtitle_foreground_opacity", DEFAULT_TEXT_OPACITY);
    }

    public static void putTextOpacity(float opacity) {
        Prefers.put("subtitle_foreground_opacity", clampOpacity(opacity));
    }

    public static int getBackgroundBaseColor() {
        return Prefers.getInt("subtitle_background_color", DEFAULT_BACKGROUND_COLOR);
    }

    public static void putBackgroundColor(int color) {
        Prefers.put("subtitle_background_color", color);
    }

    public static int getBackgroundColor() {
        return applyOpacity(getBackgroundBaseColor(), getBackgroundOpacity());
    }

    public static float getBackgroundOpacity() {
        return getOpacity("subtitle_background_opacity", DEFAULT_BACKGROUND_OPACITY);
    }

    public static void putBackgroundOpacity(float opacity) {
        Prefers.put("subtitle_background_opacity", clampOpacity(opacity));
    }

    public static int getEdgeType() {
        return Math.clamp(Prefers.getInt("subtitle_edge_type", DEFAULT_EDGE_TYPE), CaptionStyleCompat.EDGE_TYPE_NONE, CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW);
    }

    public static void putEdgeType(int edgeType) {
        Prefers.put("subtitle_edge_type", Math.clamp(edgeType, CaptionStyleCompat.EDGE_TYPE_NONE, CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW));
    }

    public static int getEdgeBaseColor() {
        return Prefers.getInt("subtitle_edge_color", DEFAULT_EDGE_COLOR);
    }

    public static void putEdgeColor(int color) {
        Prefers.put("subtitle_edge_color", color);
    }

    public static int getEdgeColor() {
        return applyOpacity(getEdgeBaseColor(), getEdgeOpacity());
    }

    public static float getEdgeOpacity() {
        return getOpacity("subtitle_edge_opacity", DEFAULT_EDGE_OPACITY);
    }

    public static void putEdgeOpacity(float opacity) {
        Prefers.put("subtitle_edge_opacity", clampOpacity(opacity));
    }

    public static float getEdgeWidth() {
        return Math.clamp(Prefers.getFloat("subtitle_edge_width", DEFAULT_EDGE_WIDTH), MIN_EDGE_WIDTH, MAX_EDGE_WIDTH);
    }

    public static void putEdgeWidth(float value) {
        Prefers.put("subtitle_edge_width", Math.clamp(value, MIN_EDGE_WIDTH, MAX_EDGE_WIDTH));
    }

    public static float getShadow() {
        return Math.clamp(Prefers.getFloat("subtitle_shadow", DEFAULT_SHADOW), MIN_SHADOW, MAX_SHADOW);
    }

    public static void putShadow(float value) {
        Prefers.put("subtitle_shadow", Math.clamp(value, MIN_SHADOW, MAX_SHADOW));
    }

    public static int getSecondaryTrackId() {
        return clampSecondaryTrackId(Prefers.getInt("subtitle_secondary_track", DEFAULT_SECONDARY_TRACK));
    }

    public static void putSecondaryTrackId(int trackId) {
        Prefers.put("subtitle_secondary_track", clampSecondaryTrackId(trackId));
    }

    public static float getSecondaryPosition() {
        return Math.clamp(Prefers.getFloat("subtitle_secondary_position", DEFAULT_SECONDARY_POSITION), MIN_SECONDARY_POSITION, MAX_SECONDARY_POSITION);
    }

    public static void putSecondaryPosition(float value) {
        Prefers.put("subtitle_secondary_position", Math.clamp(value, MIN_SECONDARY_POSITION, MAX_SECONDARY_POSITION));
    }

    private static int clampSecondaryTrackId(int trackId) {
        return Math.max(trackId, SECONDARY_SUBTITLE_OFF);
    }

    private static float getOpacity(String key, float defaultValue) {
        return clampOpacity(Prefers.getFloat(key, defaultValue));
    }

    private static float clampOpacity(float opacity) {
        return Math.clamp(opacity, MIN_OPACITY, MAX_OPACITY);
    }

    private static int applyOpacity(int color, float opacity) {
        return Color.argb(Math.round(Color.alpha(color) * clampOpacity(opacity)), Color.red(color), Color.green(color), Color.blue(color));
    }

    public static boolean isStyleForced() {
        return getStyleSource() != STYLE_SOURCE_ORIGINAL;
    }

    public static boolean isScaleForced() {
        return getScale() != DEFAULT_SCALE;
    }

    public static boolean isScaleApplied() {
        return isSystemStyle() || isScaleForced();
    }

    public static boolean isPositionSet() {
        return getPosition() != DEFAULT_POSITION;
    }

    public static CaptionStyleCompat getStyle(Context context) {
        CaptioningManager manager = (CaptioningManager) context.getSystemService(Context.CAPTIONING_SERVICE);
        if (getStyleSource() == STYLE_SOURCE_ORIGINAL) return CaptionStyleCompat.DEFAULT;
        if (isSystemStyle()) return manager == null ? CaptionStyleCompat.DEFAULT : CaptionStyleCompat.createFromCaptionStyle(manager.getUserStyle());
        return new CaptionStyleCompat(getTextColor(), getBackgroundColor(), Color.TRANSPARENT, getEdgeType(), getEdgeColor(), null, getEdgeWidth(), getShadow());
    }

    public static void applyStyle(Context context, @Nullable SubtitleView subtitleView) {
        if (subtitleView == null) return;
        subtitleView.reset();
        subtitleView.setStyle(getStyle(context));
        subtitleView.setApplyEmbeddedStyles(!isStyleForced());
        subtitleView.setApplyEmbeddedFontSizes(true);
        if (isScaleApplied()) subtitleView.setTextSizeScale(getScale(context));
        if (isPositionSet()) subtitleView.setBottomPosition(getPosition() / 100.0f);
    }

    /** 字幕时间轴偏移（毫秒）：正值字幕延后出、负值字幕提前出 */
    public static final long MIN_OFFSET_MS = -300000L;
    public static final long MAX_OFFSET_MS = 300000L;
    private static final String KEY_OFFSET = "subtitle_offset_ms";
    private static final String KEY_OFFSET_HISTORY = "subtitle_offset_history";
    private static final int MAX_OFFSET_HISTORY = 150;

    private record OffsetEntry(String key, long ms, long time) {
    }

    public static long clampOffset(long offsetMs) {
        return Math.clamp(offsetMs, MIN_OFFSET_MS, MAX_OFFSET_MS);
    }

    /** 没为某部片子调过就用这个全局值 */
    public static long getOffsetMs() {
        return clampOffset(Prefers.getLong(KEY_OFFSET, 0L));
    }

    public static void putOffsetMs(long offsetMs) {
        Prefers.put(KEY_OFFSET, clampOffset(offsetMs));
    }

    /** 按片子取偏移：key 为空（直播/本地文件等）时退回全局值 */
    public static long getOffsetMs(String key) {
        if (TextUtils.isEmpty(key)) return getOffsetMs();
        Long saved = readOffsetHistory().get(key);
        return saved == null ? getOffsetMs() : clampOffset(saved);
    }

    public static void putOffsetMs(String key, long offsetMs) {
        if (TextUtils.isEmpty(key)) {
            putOffsetMs(offsetMs);
            return;
        }
        Map<String, Long> history = readOffsetHistory();
        history.put(key, clampOffset(offsetMs));
        writeOffsetHistory(history);
    }

    public static void removeOffsetMs(String key) {
        if (TextUtils.isEmpty(key)) {
            putOffsetMs(0L);
            return;
        }
        Map<String, Long> history = readOffsetHistory();
        if (history.remove(key) != null) writeOffsetHistory(history);
    }

    public static void resetOffset() {
        Prefers.put(KEY_OFFSET, 0L);
        Prefers.remove(KEY_OFFSET_HISTORY);
    }

    private static LinkedHashMap<String, Long> readOffsetHistory() {
        LinkedHashMap<String, Long> history = new LinkedHashMap<>();
        try {
            JSONArray array = new JSONArray(Prefers.getString(KEY_OFFSET_HISTORY, ""));
            List<OffsetEntry> entries = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONArray item = array.optJSONArray(i);
                if (item == null || item.length() < 2) continue;
                entries.add(new OffsetEntry(item.optString(0), item.optLong(1), item.optLong(2)));
            }
            entries.sort(Comparator.comparingLong(OffsetEntry::time));
            for (OffsetEntry entry : entries) if (!TextUtils.isEmpty(entry.key())) history.put(entry.key(), entry.ms());
        } catch (Exception ignored) {
            // 记不住就算了，不能让一条坏数据把播放页拖崩
        }
        return history;
    }

    private static void writeOffsetHistory(Map<String, Long> history) {
        List<String> keys = new ArrayList<>(history.keySet());
        int from = Math.max(0, keys.size() - MAX_OFFSET_HISTORY);
        long now = System.currentTimeMillis();
        JSONArray array = new JSONArray();
        for (int i = from; i < keys.size(); i++) {
            JSONArray item = new JSONArray();
            item.put(keys.get(i));
            item.put(history.get(keys.get(i)));
            item.put(now - (long) (keys.size() - i)); // 保住原本的先后次序，好让最老的那批被挤掉
            array.put(item);
        }
        Prefers.put(KEY_OFFSET_HISTORY, array.toString());
    }

    public static void resetAdjust() {
        Prefers.put("subtitle_scale", DEFAULT_SCALE);
        Prefers.put("subtitle_position", DEFAULT_POSITION);
    }

    public static void resetStyle() {
        Prefers.put("subtitle_style_source", STYLE_SOURCE_ORIGINAL);
        Prefers.put("subtitle_foreground_color", DEFAULT_TEXT_COLOR);
        Prefers.put("subtitle_background_color", DEFAULT_BACKGROUND_COLOR);
        Prefers.put("subtitle_edge_type", DEFAULT_EDGE_TYPE);
        Prefers.put("subtitle_edge_color", DEFAULT_EDGE_COLOR);
        Prefers.put("subtitle_foreground_opacity", DEFAULT_TEXT_OPACITY);
        Prefers.put("subtitle_background_opacity", DEFAULT_BACKGROUND_OPACITY);
        Prefers.put("subtitle_edge_opacity", DEFAULT_EDGE_OPACITY);
        Prefers.put("subtitle_edge_width", DEFAULT_EDGE_WIDTH);
        Prefers.put("subtitle_shadow", DEFAULT_SHADOW);
    }

    public static void resetAdvanced() {
        Prefers.put("subtitle_secondary_track", DEFAULT_SECONDARY_TRACK);
        Prefers.put("subtitle_secondary_position", DEFAULT_SECONDARY_POSITION);
    }

    public static void reset() {
        resetAdjust();
        resetStyle();
        resetAdvanced();
    }
}
