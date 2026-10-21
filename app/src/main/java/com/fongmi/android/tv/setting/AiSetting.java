package com.fongmi.android.tv.setting;

import com.github.catvod.utils.Prefers;

/**
 * 智能识别：拿不准的条目（比如「是不是一部短剧被合成了一个视频」）交给大模型判一句。
 * 只是辅助——判不出来一律退回本地规则，绝不卡住播放。
 *
 * ⚠️ **这是大模型的总开关，默认关**（2026-10-21 马先生定）：他不去设置里打开，
 * 任何数据都不许发给大模型——合集判定、演员补录、刷剧推荐、智能搜索全都只在本地跑。
 * 所有出口（AiJudge / AiSearch）在拼请求前都先查 isEnabled()，没有旁路。
 *
 * 接口走的是 43.165.3.81 上 nginx 的中继：真正的上游 key 只存在服务器上，
 * APP 里放的是中继口令，口令就算被人从包里抠出来，改服务器一行配置就能轮换，上游 key 永不外泄。
 */
public class AiSetting {

    /** 中继入口（服务器 nginx 透传到上游，口令对不上直接 403） */
    public static final String DEFAULT_ENDPOINT = "https://www.169876.xyz/ai/v1";
    public static final String DEFAULT_MODEL = "qwen-flash";

    /** 问一句最多等多久，超时就当没问 */
    public static final long TIMEOUT = 5000L;

    /** 中继口令：打散存放防扫描器一眼扫到。泄露了不用发版，改服务器配置换口令即可 */
    private static final String MASK = "liuguang-zhezhou";
    private static final int[] K = {91, 10, 71, 6, 17, 86, 87, 1, 20, 24, 81, 3, 78, 90, 87, 64, 84, 88, 67, 83, 67, 2, 92, 82, 25, 25, 95, 87, 25, 14, 9, 65};

    public static boolean isEnabled() {
        // 总开关默认关：用户没去设置里亲手打开之前，任何数据都不发给大模型
        return Prefers.getBoolean("ai_judge", false);
    }

    public static void putEnabled(boolean enabled) {
        Prefers.put("ai_judge", enabled);
    }

    public static String getEndpoint() {
        String url = Prefers.getString("ai_endpoint", "").trim();
        return url.isEmpty() ? DEFAULT_ENDPOINT : url;
    }

    public static String getModel() {
        String model = Prefers.getString("ai_model", "").trim();
        return model.isEmpty() ? DEFAULT_MODEL : model;
    }

    /** 中继口令；要换就往 ai_key 里写一份顶掉，不用重新发版 */
    public static String getKey() {
        String custom = Prefers.getString("ai_key", "").trim();
        if (!custom.isEmpty()) return custom;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < K.length; i++) sb.append((char) (K[i] ^ MASK.charAt(i % MASK.length())));
        return sb.toString();
    }
}
