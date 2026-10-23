package com.fongmi.android.tv.utils;

import java.util.Locale;

/**
 * 【长效约定 · 大模型专用闸】（2026-10-21 马先生定边界，长期有效，不是可选项）
 *
 * 边界就一条：**发大模型之前先本地分析，违法的不过大模型，只本地。**
 * 凡是涉黄（含成人内容）、涉赌、涉毒以及其它明显涉嫌违法的内容，一律不许送进大模型——
 * 一个字节都不发，连缓存都不查，需要判定的地方一律当「判不了」退回本地规则。
 *
 * ⚠️ 这道闸只管大模型，**不管别的**（2026-10-21 马先生特意澄清，别再把闸往外扩）：
 * - App 里的搜索、联想词、TMDB、海报这些都是正常功能，**不拦**；
 * - 大模型的使用范围本身也有边界：**只有刷剧模式下，短剧/网剧/AI漫剧这类剧集才问大模型，
 *   单集的电影不走大模型查数据**（见 BingeTags.askable）；
 * - 演员补录（StarStrip → AiJudge.actors）照常问，但不正经的作品名在问之前就被这道闸拦下。
 *
 * 三条硬要求（改代码时照着做）：
 * 1. **唯一闸门**：判断一段内容能不能发大模型，只许调这个类的 blocked()，
 *    不准在别处另起一套词表；要加词就往下面的 WORDS 里加，全 App 一起生效。
 * 2. **先闸后问**：任何新加的大模型调用，拼 prompt 之前必须先过一遍 blocked()，
 *    命中就到此为止，不许「换个说法再问」「去掉敏感词再问」这类绕过。
 *    **例外（2026-10-24 马先生放宽）**：「AI 键搜索」这一路改成先调 scrub() 把违禁词抠掉、
 *    剩下的句子理顺再发；抠完没内容了才拒答。其余出口一律照旧一刀切。
 * 3. **连缓存都不查**：命中的条目不要回读历史答案，直接退回本地兜底，
 *    免得把旧版本留下来的脏内容又翻出来。
 *
 * 目前挂闸的大模型出口（动这些地方时记得复核闸还在）：
 * AiJudge（合集判定 / 演员补录 / 刷剧推荐词 / 下一部）、AiSearch + AiSearchRunner（智能搜索）。
 */
public final class AiGuard {

    /** 命中任意一个就不许外发。英文统一用小写写（比较时会先把文本压平并转小写） */
    private static final String[] WORDS = {
        // —— 涉黄 / 成人内容 ——
        "情色", "色情", "情色片", "色情片", "色情直播", "色情网站", "黄色", "黄色电影", "黄色录像", "黄色网站",
        "成人片", "成人电影", "成人影片", "成人影视", "成人视频", "成人内容", "成人向", "成人版", "成人专区", "成人频道", "成人动漫", "成人漫画",
        "三级片", "限制级", "18禁", "18+", "r18", "大尺度",
        "无码", "有码", "毛片", "黄片", "做爱", "性爱", "性交", "口交", "自慰", "性虐", "性侵", "猥亵", "性工作者", "炮友", "一夜情", "约炮", "招嫖", "卖淫", "嫖娼", "妓女", "雏妓", "援交",
        "裸体", "全裸", "裸聊", "裸照", "艳照", "脱衣", "脱光", "露点", "露阴", "偷拍", "换妻", "群交", "兽交", "乱伦", "淫乱", "淫秽", "迷奸", "轮奸", "强奸",
        "春药", "迷药", "催情", "性奴", "调教", "性玩具", "自慰器",
        "AV女优", "av女优", "里番", "本子", "肉蒲团", "玉蒲团", "金瓶梅",
        "porn", "porno", "pornhub", "xvideos", "xhamster", "onlyfans", "javdb", "hentai", "erotic", "nsfw", "nude",
        "xxx", "sex video", "sex tape", "adult video", "adult film", "adult movie", "pornstar",

        // —— 涉赌 ——
        "赌博", "赌场", "博彩", "六合彩", "地下赌", "网络赌", "网赌", "赌球", "赌局", "千术", "出千", "赌王争霸", "线上赌", "境外赌",

        // —— 涉毒 ——
        "毒品", "吸毒", "贩毒", "制毒", "运毒", "新型毒品", "吸毒工具", "冰毒", "海洛因", "大麻", "摇头丸", "可卡因", "罂粟", "白粉", "k粉", "笑气", "依托咪酯", "合成大麻素", "注射毒品",

        // —— 其它明显违法 / 违禁 ——
        "贩卖人口", "拐卖", "洗钱", "假币", "走私军火", "买卖枪支", "枪支弹药", "爆炸物", "制爆", "邪教", "恐怖袭击", "恐怖组织",
        "代孕", "买卖器官", "器官交易", "儿童色情", "未成年色情", "幼女",
        "台独", "疆独", "藏独", "港独", "法轮功"
    };

    private AiGuard() {
    }

    /**
     * 这些文本里有没有碰线的词。传进来的应该是准备外发的全部内容（剧名、备注、站名、搜索词等）。
     * 只要有一个命中就 true —— 调用方必须就此打住，改走本地模式。
     */
    public static boolean blocked(String... texts) {
        if (texts == null) return false;
        for (String text : texts) {
            if (hit(text)) return true;
        }
        return false;
    }

    private static boolean hit(String text) {
        if (text == null || text.isEmpty()) return false;
        String flat = flat(text);
        if (flat.isEmpty()) return false;
        for (String word : WORDS) {
            if (flat.contains(word)) return true;
        }
        return false;
    }

    /**
     * 把碰线的词从句子里抠掉，剩下的弄通顺后返回（2026-10-24 马先生定的口径）。
     *
     * 用户一口气写一大段描述，里面夹了个把违禁词不代表他要找那种片子——整句拒掉太傻，
     * 抠掉违禁词、把剩下的句子理顺再发给模型就行。全抠光了（整句都是违禁词）
     * 返回空串，调用方照旧拒答、一个字节都不外发。
     *
     * 2026-10-24 晚：马先生把这条从「只有 AI 键搜索」推广到**所有**大模型出口——
     * 推荐的第二层闸门就是这个：先本地过滤违禁词，剩下的才发模型，最后拿模型的结果去匹配。
     * 所以刷剧推荐、补演员、题材词那些出口也一律走 pass()，不再整句拒答。
     */
    public static String scrub(String text) {
        if (text == null || text.isEmpty()) return "";
        String lower = flat(text);
        if (lower.isEmpty()) return "";
        int[] map = new int[lower.length()]; // flat 的第 k 个字符在原文里的位置
        int k = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '　' || c == '-' || c == '_' || c == '·' || c == '.') continue;
            if (k < map.length) map[k++] = i;
        }
        boolean[] drop = new boolean[text.length()];
        for (String word : WORDS) {
            int from = 0;
            while (from <= lower.length() - word.length()) {
                int at = lower.indexOf(word, from);
                if (at < 0) break;
                for (int i = at; i < at + word.length() && i < map.length; i++) drop[map[i]] = true;
                from = at + word.length();
            }
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) if (!drop[i]) sb.append(text.charAt(i));
        return tidy(sb.toString());
    }

    /**
     * 二级闸门的第二段：把准备发给模型的多段文本**各抠一遍**违禁词，抠完还剩下的才返回。
     *
     * 第 0 段是主文本（剧名 / 搜索词），其余是配料（备注、站名、类型名）。
     * 主文本原来有内容、抠完却空了 —— 说明整句都是违禁词，返回 null，调用方就此打住不外发；
     * 配料抠空了没关系，留空串继续。没碰线的文本原样（顺带理顺）返回。
     */
    public static String[] pass(String... texts) {
        if (texts == null) return null;
        String[] out = new String[texts.length];
        for (int i = 0; i < texts.length; i++) out[i] = scrub(texts[i]);
        String first = texts.length > 0 ? texts[0] : null;
        if (first != null && !first.trim().isEmpty() && out[0].isEmpty()) return null;
        return out;
    }

    /** 抠完词之后把句子理顺：并掉多余空格、去掉空括号、掐掉首尾挂着的标点 */
    private static String tidy(String text) {
        String s = text.replaceAll("[ \\t　]+", " ").trim();
        s = s.replace("（）", "").replace("()", "").replace("[]", "").replace("《》", "").replace("【】", "");
        s = s.replaceAll("[，,]{2,}", "，").replaceAll("[、]{2,}", "、");
        s = s.replaceAll("^[，,、；;。.！!？?：:]+", "").replaceAll("[，,、；;。.！!？?：:]+$", "");
        return s.trim();
    }

    /** 抹掉大小写和中间夹的空格、点、杠这类花招（“P O R N”“porn-hub”也得认出来），中文不受影响 */
    private static String flat(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '　' || c == '-' || c == '_' || c == '·' || c == '.') continue;
            sb.append(c);
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }
}
