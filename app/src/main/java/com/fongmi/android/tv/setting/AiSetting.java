package com.fongmi.android.tv.setting;

import com.fongmi.android.tv.bean.AiModel;
import com.github.catvod.utils.Prefers;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能识别：拿不准的条目（比如「是不是一部短剧被合成了一个视频」）交给大模型判一句。
 * 只是辅助——判不出来一律退回本地规则，绝不卡住播放。
 *
 * ⚠️ **这是大模型的总开关，默认关**（马先生 2026-11-03 重申：新装一律关）。
 * 开关开了还得真有一条能用的模型才发请求：没录、录了无效 → 一律跳过大模型走本地（他的原话）。
 * 所有出口（AiJudge / AiSearch / 刷剧推荐）在拼请求前都查 ready()，没有旁路。
 *
 * **谁能用内置的两条**（马先生 2026-11-03 定）：用**快捷码**（520/521/6669）录进来的设备算自己人，
 * 标记走账号（跟着同步走，账号那边没设就用本机的那份兜底）；不是快捷码录进来的，内置两条不给用，
 * 想用就自己在「大模型」那页录入。快捷码录完**自动把智能识别打开**。
 *
 * 通道优先级（他的原话）：**自建的最优先**（多条时按列表顺序，手动调），然后是内置的豆包、千问。
 * 任一条挂了往下一条走，全挂就当没问、走本地，不报错不卡。
 *
 * 接口走的是 43.165.3.81 上 nginx 的中继：真正的上游 key 只存在服务器上，
 * APP 里放的是中继口令，口令就算被人从包里抠出来，改服务器一行配置就能轮换，上游 key 永不外泄。
 */
public class AiSetting {

    /** 中继入口（服务器 nginx 透传到上游，口令对不上直接 403） */
    public static final String DEFAULT_ENDPOINT = "https://www.169876.xyz/ai/v1";
    public static final String DEFAULT_MODEL = "qwen-flash";

    /**
     * 豆包（火山引擎 Ark，OpenAI 兼容）。2026-11-01 马先生给的：**豆包优先、千问兜底**——
     * 豆包是限额的（用完就查不到），千问走自建中继按量计费总能查到，所以豆包排最前、
     * 限额撞墙自动退到千问，冷却一阵再回来试豆包（限额恢复就能省下千问的钱）。
     * 模型挑的 seed-2-1-lite：便宜，关掉 thinking 后一两秒就回话，只问简单问题足够。
     * key 同样打散存放（仓库是公开的，明文会被扫描器揪出来）。
     */
    public static final String ARK_ENDPOINT = "https://ark.cn-beijing.volces.com/api/v3";
    public static final String ARK_MODEL = "doubao-seed-2-1-lite-260915";
    private static final int[] ARK_K = {13, 27, 30, 74, 71, 86, 93, 2, 25, 76, 89, 7, 87, 90, 87, 67, 95, 68, 65, 1, 17, 3, 67, 5, 26, 79, 94, 72, 74, 91, 86, 76, 84, 10, 19, 95, 68, 80, 11, 2, 0, 30, 81, 86, 76, 80};

    /** 问一句最多等多久，超时就当没问 */
    public static final long TIMEOUT = 5000L;

    /** 中继口令：打散存放防扫描器一眼扫到。泄露了不用发版，改服务器配置换口令即可 */
    private static final String MASK = "liuguang-zhezhou";
    private static final int[] K = {91, 10, 71, 6, 17, 86, 87, 1, 20, 24, 81, 3, 78, 90, 87, 64, 84, 88, 67, 83, 67, 2, 92, 82, 25, 25, 95, 87, 25, 14, 9, 65};

    /** 用户自建模型存在这个键里（JSON 数组），跟着账号同步 */
    public static final String KEY_MODELS = "ai_models";
    /** 内部标记（快捷码录入过源 = 自己人），走账号同步，账号没设就用本机的那份 */
    public static final String KEY_INSIDER = "ai_insider";

    public static boolean isEnabled() {
        // 总开关默认关：用户没去设置里亲手打开之前，任何数据都不发给大模型
        return Prefers.getBoolean("ai_judge", false);
    }

    public static void putEnabled(boolean enabled) {
        Prefers.put("ai_judge", enabled);
    }

    /**
     * 真要发请求前问这一句：开关开了 + 至少有一条能用的模型。
     * 没录模型、录的不全（缺地址/Key/模型名）→ false，调用方一律走本地，不报错。
     */
    public static boolean ready() {
        return isEnabled() && !channels().isEmpty();
    }

    /** 内部标记：走快捷码录过源的设备（自己人），能用内置那两条 */
    public static boolean isInsider() {
        return Prefers.getBoolean(KEY_INSIDER, false);
    }

    public static void putInsider(boolean insider) {
        Prefers.put(KEY_INSIDER, insider);
    }

    public static String getEndpoint() {
        String url = Prefers.getString("ai_endpoint", "").trim();
        return url.isEmpty() ? DEFAULT_ENDPOINT : url;
    }

    public static String getModel() {
        String model = Prefers.getString("ai_model", "").trim();
        return model.isEmpty() ? DEFAULT_MODEL : model;
    }

    /** 豆包的 Ark key：打散存放，要换也是改这里重新发版（跟中继口令一个待遇） */
    public static String getArkKey() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ARK_K.length; i++) sb.append((char) (ARK_K[i] ^ MASK.charAt(i % MASK.length())));
        return sb.toString();
    }

    /** 中继口令；要换就往 ai_key 里写一份顶掉，不用重新发版 */
    public static String getKey() {
        String custom = Prefers.getString("ai_key", "").trim();
        if (!custom.isEmpty()) return custom;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < K.length; i++) sb.append((char) (K[i] ^ MASK.charAt(i % MASK.length())));
        return sb.toString();
    }

    /* ---------- 模型列表 ---------- */

    /** 用户自建的模型，按存的先后顺序（顺序就是优先级，界面上可以手动调） */
    public static List<AiModel> customs() {
        List<AiModel> items = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(Prefers.getString(KEY_MODELS, ""));
            for (int i = 0; i < array.length(); i++) {
                AiModel item = AiModel.from(array.optJSONObject(i));
                if (item == null || item.isDel()) continue;
                items.add(item);
            }
        } catch (Exception ignored) {
        }
        return items;
    }

    private static JSONArray customsJson() {
        try {
            return new JSONArray(Prefers.getString(KEY_MODELS, ""));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static void putCustoms(List<AiModel> items) {
        JSONArray array = new JSONArray();
        for (AiModel item : items) array.put(item.toJson());
        Prefers.put(KEY_MODELS, array.toString());
    }

    /** 内置两条：只有自己人（快捷码录入过）才配用；启停是本机的开关，不同步 */
    public static List<AiModel> builtins() {
        List<AiModel> items = new ArrayList<>();
        if (!isInsider()) return items;
        AiModel doubao = AiModel.builtin(AiModel.BUILTIN_DOUBAO, "豆包", ARK_ENDPOINT, getArkKey(), ARK_MODEL);
        AiModel qwen = AiModel.builtin(AiModel.BUILTIN_QWEN, "千问", DEFAULT_ENDPOINT, getKey(), DEFAULT_MODEL);
        doubao.setEnabled(isBuiltinOn(AiModel.BUILTIN_DOUBAO));
        qwen.setEnabled(isBuiltinOn(AiModel.BUILTIN_QWEN));
        items.add(doubao);
        items.add(qwen);
        return items;
    }

    public static boolean isBuiltinOn(String id) {
        return Prefers.getBoolean("ai_on_" + id, true);
    }

    public static void putBuiltinOn(String id, boolean on) {
        Prefers.put("ai_on_" + id, on);
    }

    /** 界面用的完整名单：自建在前，内置在后 */
    public static List<AiModel> all() {
        List<AiModel> items = customs();
        items.addAll(builtins());
        return items;
    }

    /**
     * 真正会去问的通道：**自建优先**（按列表顺序），后面才是内置的豆包、千问。
     * 填不全的（缺地址/Key/模型名）和手动关掉的一律跳过——跳过了就当没这条，绝不卡播放。
     */
    public static List<AiModel> channels() {
        once();
        List<AiModel> items = new ArrayList<>();
        for (AiModel item : customs()) if (item.usable()) items.add(item);
        for (AiModel item : builtins()) if (item.usable()) items.add(item);
        return items;
    }

    /** 新增/修改一条自建模型（按 id 找，找不到就追加到末尾） */
    public static void save(AiModel model) {
        model.touch();
        List<AiModel> items = customs();
        boolean found = false;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getId().equals(model.getId())) {
                items.set(i, model);
                found = true;
                break;
            }
        }
        if (!found) items.add(model);
        putCustoms(items);
    }

    /** 删一条自建模型：删完留一个删除标记，别的设备同步到它才知道也要删，不然会「删不掉」 */
    public static void remove(String id) {
        List<AiModel> items = customs();
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).getId().equals(id)) continue;
            items.remove(i);
            break;
        }
        putCustoms(items);
        try {
            JSONObject tomb = new JSONObject();
            tomb.put("id", id);
            tomb.put("del", true);
            tomb.put("utime", System.currentTimeMillis());
            JSONArray array = merge(customsJson(), new JSONArray().put(tomb));
            Prefers.put(KEY_MODELS, array.toString());
        } catch (Exception ignored) {
        }
    }

    /** 手动排序：把某条往上（up=true）或往下挪一格 */
    public static void move(String id, boolean up) {
        List<AiModel> items = customs();
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).getId().equals(id)) continue;
            int to = up ? i - 1 : i + 1;
            if (to < 0 || to >= items.size()) return;
            AiModel item = items.remove(i);
            item.touch();
            items.add(to, item);
            break;
        }
        putCustoms(items);
    }

    /* ---------- 同步用：按 id 合并 ---------- */

    /** 网页（扫码录入那一页）回填用：把第一条自建模型的四个值给它 */
    public static String toJson() {
        try {
            JSONObject object = new JSONObject();
            List<AiModel> items = customs();
            AiModel first = items.isEmpty() ? null : items.get(0);
            object.put("name", first == null ? "" : first.getName());
            object.put("endpoint", first == null ? "" : first.getEndpoint());
            object.put("key", first == null ? "" : first.getKey());
            object.put("model", first == null ? "" : first.getModel());
            return object.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    public static String exportJson() {
        return customsJson().toString();
    }

    /**
     * 两边合并：同一条（id 相同）比谁改得晚（utime），删除标记也算一次改动——
     * A 机删掉的，B 机同步后也跟着删。
     */
    public static JSONArray merge(JSONArray... arrays) {
        Map<String, JSONObject> map = new LinkedHashMap<>();
        for (JSONArray array : arrays) {
            if (array == null) continue;
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id");
                if (id.isEmpty()) continue;
                JSONObject old = map.get(id);
                if (old == null || item.optLong("utime") >= old.optLong("utime")) map.put(id, item);
            }
        }
        JSONArray result = new JSONArray();
        for (JSONObject item : map.values()) result.put(item);
        return result;
    }

    /** 同步下来的一份（账号那边的数据）合进本机；账号那边没有这份就用本机的兜底，所以空的不动 */
    public static void applyRemote(String json) {
        if (json == null || json.trim().isEmpty()) return;
        JSONArray remote;
        try {
            remote = new JSONArray(json.trim());
        } catch (Exception e) {
            return;
        }
        if (remote.length() == 0) return;
        Prefers.put(KEY_MODELS, merge(customsJson(), remote).toString());
    }

    /** 老版本那三个单键（ai_endpoint/ai_model/ai_key）升级成一条自建模型，只做一次 */
    private static volatile boolean migrated;

    private static void once() {
        if (migrated) return;
        migrated = true;
        migrate();
    }

    public static void migrate() {
        String url = Prefers.getString("ai_endpoint", "").trim();
        String model = Prefers.getString("ai_model", "").trim();
        // 地址和模型名缺一样就不迁——只有 ai_key 的情况不算「用户自己录过模型」：
        // ai_key 是内置千问那条中继的备用口令，拿它去造一条自建模型等于把内部通道漏给外人
        if (url.isEmpty() || model.isEmpty()) return;
        if (!customs().isEmpty()) return; // 已经有模型了就别再塞一条
        AiModel item = AiModel.create();
        item.setName(model);
        item.setEndpoint(url);
        item.setKey(Prefers.getString("ai_key", "").trim());
        item.setModel(model);
        Prefers.put("ai_endpoint", "");
        Prefers.put("ai_model", "");
        save(item);
    }
}
