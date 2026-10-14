package com.fongmi.android.tv.playback.vod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 短剧题材词库与标题小工具：判定是不是短剧、从标题里联想同类关键词、排除解说合集 */
public class BingeTags {

    /**
     * 题材族：同族算「同类换个说法」，跨族算「换个方向」。
     * 刷剧换下一部时优先换族，族内再轮转，防止一路死磕同一个词刷成信息茧房（红果的「相关设定延伸」思路）。
     * 每族第一个词是族代表：剧名里抠不出该族的词时，拿它当搜索词兜底。
     */
    public static final String[][] FAMILIES = {
        // 古装 / 宫斗 / 宅斗
        {"王妃", "王爷", "宫斗", "宅斗", "太子", "公主", "长公主", "郡主", "世子", "摄政", "太后", "太妃", "娘娘", "贵妃", "冷宫", "废后", "选妃", "和亲", "替嫁", "冲喜",
         "主母", "小妾", "姨娘", "侧妃", "通房", "嫡女", "庶女", "夫人", "少奶奶", "内宅", "后宫", "朝堂", "权臣", "驸马", "皇帝", "皇叔", "国师", "太监", "宫女", "世家", "贵女"},
        // 重生 / 穿越 / 系统流
        {"重生", "穿越", "穿书", "系统", "签到", "金手指", "空间", "读心", "时间循环", "马甲", "隐藏身份", "扮猪吃虎", "无限流", "副本", "规则怪谈", "觉醒"},
        // 现代都市 / 婚恋
        {"总裁", "霸总", "豪门", "首富", "隐婚", "闪婚", "契约婚姻", "先婚后爱", "娃娃亲", "联姻", "婚约", "前夫", "前妻", "离婚", "追妻", "破镜重圆",
         "替身", "白月光", "甜宠", "虐恋", "暗恋", "姐弟恋", "青梅竹马", "秘书", "保镖", "办公室", "职场", "小三", "出轨"},
        // 男频逆袭
        {"复仇", "逆袭", "战神", "龙王", "神豪", "赘婿", "上门女婿", "废柴", "废婿", "三年之期", "大佬", "隐世", "退伍", "兵王", "杀手", "特工"},
        // 玄幻 / 修仙 / 异能
        {"修仙", "仙尊", "帝尊", "师尊", "神医", "神医出山", "鉴宝", "天师", "末世", "丧尸", "抽卡", "异能", "炼丹", "宗门", "剑尊", "魔尊", "妖妃", "狐妖"},
        // 家庭伦理
        {"婆媳", "萌宝", "带球跑", "带娃", "萌娃", "单亲", "继母", "后妈", "重男轻女", "赡养", "二婚", "寻亲", "真假千金", "真假少爷", "弃妇", "弃子", "保姆", "凤凰男"},
        // 校园 / 行业 / 悬疑
        {"校园", "校霸", "高考", "高三", "电竞", "网红", "直播", "明星", "娱乐圈", "选秀", "医生", "护士", "警察", "律师", "法医", "探案", "悬疑"},
        // 乡村 / 种田 / 女频成长
        {"乡村", "种田", "致富", "守寡", "和离", "休妻", "入赘", "继妹", "闺蜜", "渣男", "失忆", "大女主", "女强", "团宠"},
        // AI 漫剧 / 动态漫题材
        {        "漫剧", "动态漫", "沙雕漫", "动态漫画", "条漫", "AI漫剧", "二次元", "国漫", "动漫", "漫画", "漫改",
        "诡异", "异兽", "升级", "卡牌", "废土", "机甲", "星际", "虫族", "兽人", "血族", "狼人", "御兽"}
    };

    /** 产地：跟着当前这部剧走，刷国产一路国产，刷韩剧一路韩剧 */
    public static final String CN = "CN";
    public static final String KR = "KR";
    public static final String JP = "JP";
    public static final String US = "US";
    public static final String TH = "TH";
    public static final String HK = "HK";
    public static final String TW = "TW";
    public static final String OT = "OT";

    /** 各地区特征词：地区名、语种、常见平台 */
    private static final String[][] REGION_WORDS = {
        {KR, "韩剧", "韩语", "韩版", "韩流", "韩式", "韩国", "韩漫", "朝鲜", "欧巴", "欧尼", "财阀", "青瓦台", "首尔", "泡菜", "KBS", "SBS", "tvN", "MBC", "JTBC"},
        {JP, "日剧", "日语", "日版", "日式", "日本", "日漫", "东京", "大阪", "和风"},
        {US, "美剧", "美版", "欧美", "美国", "好莱坞", "Netflix", "netflix", "HBO", "hbo", "BBC", "Disney", "Amazon", "英剧", "英国"},
        {TH, "泰剧", "泰语", "泰国"},
        {HK, "港剧", "香港", "TVB", "tvb"},
        {TW, "台剧", "台湾"},
        {OT, "越南", "印度", "土耳其", "俄罗斯", "西班牙", "德国", "法国", "意大利", "巴西", "墨西哥", "荷兰", "澳洲", "澳大利亚", "加拿大", "新加坡", "马来西亚", "菲律宾", "印尼", "伊朗", "海外", "外网", "外语", "外剧", "国外"}
    };

    /** 抗战题材：别因为剧名里带了"日本"就把国产抗战剧当外剧 */
    private static final String[] ANTI_JAPAN = {"抗日", "鬼子", "八路", "新四军", "抗战", "远征军", "南京大屠杀"};

    private static final Pattern HANGUL = Pattern.compile("[\\uac00-\\ud7af\\u1100-\\u11ff]");
    private static final Pattern KANA = Pattern.compile("[\\u3040-\\u30ff]");
    private static final Pattern THAI = Pattern.compile("[\\u0e00-\\u0e7f]");
    private static final Pattern CYRILLIC = Pattern.compile("[\\u0400-\\u04ff]");
    private static final Pattern HANZI = Pattern.compile("[\\u4e00-\\u9fa5]");

    /** 判产地：地区词 → 原文字符 → 有汉字算国产；判不出来返回空串 */
    public static String regionOf(String text) {
        if (text == null || text.isEmpty()) return "";
        for (String anti : ANTI_JAPAN) if (text.contains(anti)) return CN; // 国产抗战剧
        for (String[] group : REGION_WORDS) {
            for (int i = 1; i < group.length; i++) if (text.contains(group[i])) return group[0];
        }
        if (HANGUL.matcher(text).find()) return KR;
        if (KANA.matcher(text).find()) return JP;
        if (THAI.matcher(text).find()) return TH;
        if (CYRILLIC.matcher(text).find()) return OT;
        if (HANZI.matcher(text).find()) return CN;
        return ""; // 一个中文字都没有，看不出是哪里的，先不判死
    }

    /** 候选产地要跟当前这部一致：刷国产一路国产，刷韩剧一路韩剧 */
    public static boolean sameRegion(String current, String text) {
        if (current == null || current.isEmpty()) return true; // 当前判不出产地，不挑
        String region = regionOf(text);
        if (region.isEmpty() || region.equals(current)) return true; // 看不出产地的先放行
        if (current.equals(CN)) return false; // 刷国产：凡明确是外剧的都不要
        if (region.equals(CN)) return true; // 刷外剧：中文译名看不出国籍，放行，靠搜索词和详情兜底
        return false; // 刷韩剧时冒出日剧美剧，不要
    }

    /** 搜索时带上的产地词，保证搜出来的就是同一国的 */
    public static String regionWord(String region) {
        if (KR.equals(region)) return "韩剧";
        if (JP.equals(region)) return "日剧";
        if (US.equals(region)) return "美剧";
        if (TH.equals(region)) return "泰剧";
        if (HK.equals(region)) return "港剧";
        if (TW.equals(region)) return "台剧";
        return "";
    }

    /**
     * 剧种：刷剧一路只刷同一种，**不许串**（马先生 2026-10-12：看正剧就一路正剧，看短剧/广剧/AI漫剧就照这个类型往下刷）。
     * 红果那边的分法也是真人剧、漫剧各走各的池子，串着推会让"我明明在刷短剧，忽然跳一部 45 分钟的正剧"。
     */
    public static final int KIND_UNKNOWN = 0;
    public static final int KIND_SHORT = 1; // 真人短剧（广剧也算这一类，投放渠道不同而已）
    public static final int KIND_COMIC = 2; // 漫剧 / AI漫剧 / 动态漫
    public static final int KIND_LONG = 3;  // 正剧：横屏、三四十集、一集四五十分钟

    /** 单集到这个时长基本就是正剧了（短剧一般 1~5 分钟，长一点的也就十来分钟） */
    public static final long LONG_EPISODE_MS = 15 * 60 * 1000L;

    /**
     * 名字/分类上明写着正剧那一挂。⚠️ 只留**证据硬**的几个：
     * 「港剧/台剧」是产地词不是剧种词（刷港剧短剧会被误判成正剧），「网剧/网络剧」短剧站也爱这么叫，都不收。
     */
    private static final String[] LONG_MARKS = {"电视剧", "国产剧", "正剧", "连续剧", "剧情片"};
    /** 广剧：短剧按投放渠道分出来的叫法，归到短剧这一类 */
    private static final String[] AD_MARKS = {"广剧"};

    /** 低于这个集数基本是电影、单集综艺，没法一部接一部刷 */
    public static final int MIN_PLAYABLE = 8;
    /** 集数够这么多就刷，只是判据之一，不是门槛 */
    public static final int MIN_EPISODES = 20;
    /** 单集时长上限：短剧/漫剧一般 1~4 分钟，放宽到 5 分钟 */
    public static final long SHORT_EPISODE_MS = 300_000L;
    /** 动态漫的扣分：正常候选得分也就 0~10 分，扣这么多等于排队尾，只有没别人了才轮到它 */
    public static final int DYNAMIC_PENALTY = 30;
    /** 画像里动态漫权重到最强题材的这个比例，就算「他就是爱看动态漫」，不再压它 */
    public static final double DYNAMIC_PREFER_RATIO = 0.5;
    /** 或者动态漫占了总兴趣的这个比例，也算明确偏好 */
    public static final double DYNAMIC_PREFER_SHARE = 0.25;

    /** 能不能一部接一部刷：多集即可，短剧/漫剧优先，别卡死门槛 */
    public static boolean canBinge(int episodes, long duration, String name) {
        if (episodes >= MIN_PLAYABLE) {
            if (episodes >= MIN_EPISODES) return true;
            if (isShortMark(name)) return true;
            return duration <= 0 || duration <= SHORT_EPISODE_MS;
        }
        // 集数不到 8：可能是整部短剧被压成一个视频的合集版，本地评分说了算
        return compileScore(name, "", episodes, duration) >= FALLBACK_YES;
    }

    /** 名字上明写着「整部合在一起」 */
    public static boolean hasCompileMark(String name) {
        if (name == null || name.isEmpty()) return false;
        for (String mark : COMPILE_MARKS) if (name.contains(mark)) return true;
        return false;
    }

    /** 名字里有没有题材词（短剧语境加分） */
    public static boolean hasTag(String name) {
        if (name == null || name.isEmpty()) return false;
        for (String tag : SORTED_TAGS) if (name.contains(tag)) return true;
        return false;
    }

    /**
     * 「一整部短剧被合成一个视频」的本地评分，0~100。
     * 70 集 × 1 分钟 = 一个 70 分钟的单集，内容就是那部剧，不能因为只有一集就排除。
     */
    public static int compileScore(String name, String remarks, int episodes, long duration) {
        if (episodes > 1) return 0; // 分集齐全，本来就是一集一集的
        if (isBanned(name)) return 0; // 解说、混剪这些再像也不是正片
        int score = 0;
        if (hasCompileMark(name)) score += 65; // 名字明写合集/完整版，八九不离十
        int total = parseEpisodes(remarks);
        if (total >= MIN_EPISODES) score += 40; // 备注写着「全70集」，装的就是整部
        else if (total >= MIN_PLAYABLE) score += 25;
        if (episodes == 1 && duration >= COMPILE_MIN_MS) score += 35; // 单集但时长是一部剧的量级：灰色地带，交模型
        else if (episodes == 0 && duration >= COMPILE_MIN_MS) score += 15;
        if (isShortMark(name)) score += 20; // 名字带短剧/漫剧字样
        if (hasTag(name)) score += 10; // 命中题材词，短剧语境
        return Math.min(score, 100);
    }

    /**
     * 阈值判定：本地评分 ≥70 直接判「是」（不进模型），≤30 直接判「不是」（也不进模型），
     * 只有中间的灰色地带才返回 COMPILE_ASK，交给大模型问一句。
     */
    public static int compileOf(String name, String remarks, int episodes, long duration) {
        int score = compileScore(name, remarks, episodes, duration);
        if (score >= THRESHOLD_YES) return COMPILE_YES;
        if (score <= THRESHOLD_NO) return COMPILE_NO;
        return COMPILE_ASK;
    }

    /** 大模型不可用时的本地兜底：评分到 50 就当它是合集，按一部剧对待 */
    public static boolean compileFallback(String name, String remarks, int episodes, long duration) {
        return compileScore(name, remarks, episodes, duration) >= FALLBACK_YES;
    }

    /** 大模型缓存键：同名同备注只问一次 */
    public static String compileKey(String name, String remarks, int episodes, long duration) {
        return name + "|" + remarks + "|" + episodes + "|" + (duration / 60000);
    }

    /**
     * 「动态漫」的说法。⚠️ 只有这一类要降级——马先生 2026-10-11 特意纠正：
     * **漫剧是漫剧，动态漫是动态漫，两码事，别混成一个词表。**
     */
    public static final String[] DYNAMIC_MARKS = {
        "动态漫", "动态漫画", "沙雕漫", "条漫", "AI漫剧", "AI 漫剧", "ai漫剧"
    };

    /** 广义「漫剧」的说法（不含动态漫那几个词，动态漫另算）：漫剧、动漫、漫画、二次元这些都算 */
    private static final String[] COMIC_MARKS = {
        "漫剧", "动漫", "漫画", "二次元", "国漫", "日漫", "韩漫", "漫改"
    };

    /** 是不是动态漫：只有这类在没有偏好数据时才压到最后 */
    public static boolean isDynamic(String name) {
        if (name == null || name.isEmpty()) return false;
        for (String mark : DYNAMIC_MARKS) if (name.contains(mark)) return true;
        return false;
    }

    /** 是不是漫剧大类（动态漫也算漫剧，但降级只降动态漫，不降漫剧） */
    public static boolean isComic(String name) {
        if (name == null || name.isEmpty()) return false;
        for (String mark : COMIC_MARKS) if (name.contains(mark)) return true;
        return isDynamic(name);
    }

    /** 漫剧在 FAMILIES 里是最后一族：只有当前这部本身就是漫剧时才往这边跳 */
    public static int comicFamily() {
        return FAMILIES.length - 1;
    }

    /**
     * 画像里动态漫的权重够不够算「他就爱看动态漫」：到最强题材的一半，或者占了总兴趣的四分之一，就算。
     * 纯函数，好单测。判不出来（权重 0 = 一部动态漫都没看完过）就按「没有基础数据」处理。
     */
    public static boolean dynamicPreferredOf(double dynamicWeight, double maxWeight, double totalWeight) {
        if (dynamicWeight <= 0 || maxWeight <= 0) return false;
        return dynamicWeight >= maxWeight * DYNAMIC_PREFER_RATIO || dynamicWeight >= totalWeight * DYNAMIC_PREFER_SHARE;
    }

    /** 名字上就写着是短剧/微剧/AI漫剧的标记，命中即按短剧对待 */
    private static final String[] SHORT_MARKS = {
        "短剧", "微剧", "微短剧", "小剧场", "迷你剧", "竖屏", "泡面番",
        "漫剧", "动态漫", "沙雕漫", "动态漫画", "条漫", "漫改", "AI漫剧"
    };

    /** 标题里的噪声，清掉再取关键词 */
    private static final String[] NOISE = {
        "全集", "完整版", "高清", "超清", "免费", "在线观看", "在线播放", "手机版", "国语", "普通话", "中字", "未删减", "抢先看", "独播",
        "抖音", "快手", "小红书", "微信", "小程序", "热门", "爆款", "新剧", "短剧", "网剧", "电视剧", "影视", "视频", "影视解说",
        "微剧", "微短剧", "竖屏", "漫剧", "动态漫", "沙雕漫", "动态漫画", "条漫", "AI", "ai",
        "第一季", "第二季", "第三季", "上部", "下部", "上集", "下集", "大结局", "结局", "未完待续", "更新中"
    };

    /**
     * 不是一集一集正片的东西：解说、混剪、盘点，一律不刷。
     * 注意「合集 / 完整版 / 一口气」从这里挪走了：有些站就是把整部短剧压成一个视频，
     * 名字上带这些词，但它就是那部短剧本身，不能一刀切（见 compileOf）。
     */
    private static final String[] BANNED = {
        "解说", "速看", "几分钟", "盘点", "混剪", "剪辑", "cut", "CUT", "花絮", "预告", "番外", "幕后", "纯享", "加长",
        "抢先版", "精彩片段", "高光", "名场面", "reaction", "Reaction", "吐槽", "点评", "解析", "剧情介绍", "分集剧情", "剧情解说",
        "电影", "大电影", "剧场版", "MV", "主题曲", "插曲", "花絮合集", "幕后花絮"
    };

    /** 合集判定：是 / 不是 / 拿不准（拿不准的交给大模型） */
    public static final int COMPILE_NO = 0;
    public static final int COMPILE_YES = 1;
    public static final int COMPILE_ASK = 2;

    /** 阈值：本地评分到 70 直接判「是」，不到 30 直接判「不是」，中间才去问大模型 */
    public static final int THRESHOLD_YES = 70;
    public static final int THRESHOLD_NO = 30;
    /** 大模型不可用时的本地兜底线：评分到 50 就当它是合集 */
    public static final int FALLBACK_YES = 50;

    /** 名字上明写着整部合在一起 */
    private static final String[] COMPILE_MARKS = {
        "合集", "合辑", "总集", "全剧", "全集版", "完整版", "整部", "一口气", "全程", "连播", "一次看", "看完"
    };

    /** 单集但时长到了一部短剧的量级，下限 20 分钟 */
    public static final long COMPILE_MIN_MS = 20L * 60 * 1000L;

    /**
     * 续集后缀，去掉后可拿主标题找同系列。
     * ⚠️ 季号阿拉伯数字、中文数字、罗马数字都得认：站源写法五花八门——《权力的游戏 第二季》《庆余年2》
     * 《XX 第3季》《XX S4》《XX Ⅱ》都是同一个意思，抠主标题时必须一视同仁地削掉，
     * 否则「庆余年2」抠出来是「庆余年」、「庆余年3」抠出来是「庆余年3」，两边对不上就接不下去。
     * 数字尾缀要求前面不是字母/数字：「XXS2」里的 2 交给英文那条处理（否则会削成「XXS」），
     * 「请回答1988」末尾的 88 前面是数字，那是年份不是季号，不能削。
     */
    private static final Pattern SERIAL_SUFFIX = Pattern.compile("(第[一二三四五六七八九十\\d]+[部季篇章]|之[\\u4e00-\\u9fa5]{1,6}|续集|续|[ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩⅪⅫ]{1,2}|(?<![a-zA-Z\\d])\\d{1,2}|下部|下部篇)$");

    /**
     * 系列序号的几种写法：中文「第二季 / 第2部」、英文「S2 / S02 / Season 2」、罗马数字「Ⅱ」。
     * ⚠️ 英文那条要求 S 前面不是字母、后面紧跟数字，免得把「House」「Sos」这类名字里的 s 当成季号。
     */
    private static final Pattern SERIAL_CN = Pattern.compile("第\\s*([一二三四五六七八九十]+|\\d{1,2})\\s*[部季篇章]");
    private static final Pattern SERIAL_EN = Pattern.compile("(?i)(?<![a-z])(?:season\\s*|s)0?(\\d{1,2})(?![a-z\\d])");
    private static final Pattern SERIAL_ROMAN = Pattern.compile("[ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩⅪⅫ]{1,2}");
    private static final Pattern SERIAL_TAIL = Pattern.compile("(?<![集章话部季篇a-zA-Z\\d])(\\d{1,2})$");
    private static final String[] ROMAN = {"Ⅰ", "Ⅱ", "Ⅲ", "Ⅳ", "Ⅴ", "Ⅵ", "Ⅶ", "Ⅷ", "Ⅸ", "Ⅹ", "Ⅺ", "Ⅻ"};

    private static final Pattern EPISODES = Pattern.compile("(\\d{1,4})\\s*[集章话]");

    private static final List<String> SORTED_TAGS = sortedTags();

    private static List<String> sortedTags() {
        List<String> tags = new ArrayList<>();
        for (String[] family : FAMILIES) for (String tag : family) if (!tags.contains(tag)) tags.add(tag);
        tags.sort(Comparator.comparingInt(String::length).reversed());
        return tags;
    }

    /** 题材词属于哪一族（见 FAMILIES），查不到返回 -1 */
    public static int familyOf(String tag) {
        if (tag == null || tag.isEmpty()) return -1;
        for (int i = 0; i < FAMILIES.length; i++) {
            for (String word : FAMILIES[i]) if (word.equals(tag)) return i;
        }
        return -1;
    }

    /** 族代表词：剧名里抠不出该族的词时，拿它当搜索词 */
    public static String familyHead(int family) {
        return family >= 0 && family < FAMILIES.length ? FAMILIES[family][0] : "";
    }

    /** 族里找一个不在 excluded 里的词，族内轮转用；全用过了返回 null */
    public static String familyAlt(int family, List<String> excluded) {
        if (family < 0 || family >= FAMILIES.length) return null;
        for (String word : FAMILIES[family]) if (excluded == null || !excluded.contains(word)) return word;
        return null;
    }

    /** 从剧名里抽出命中的题材词，长词优先、去重；漫剧/古风这些一个都不漏 */
    public static List<String> tagsOf(String name) {
        List<String> tags = new ArrayList<>();
        if (name == null || name.isEmpty()) return tags;
        for (String tag : SORTED_TAGS) if (name.contains(tag) && !tags.contains(tag)) tags.add(tag);
        return tags;
    }

    /** 洗标题：去括号、去噪声，剩下的才是能联想的部分 */
    public static String clean(String name) {
        if (name == null) return "";
        String text = halfWidth(name).replaceAll("[《》〈⟩\\[\\]【】()（）·・:：,，。.!！?？\"'“”‘’|/\\\\-_~\\s]", "");
        for (String noise : NOISE) text = text.replace(noise, "");
        return text.trim();
    }

    /**
     * 剧名里写着第几季 / 第几部（「权力的游戏 第一季」→ 1）；一个都没写返回 -1。
     * 这是「系列剧」的硬证据：有它就能一季一季往下接，而不是换一部别的剧。
     */
    public static int serialOf(String name) {
        if (name == null || name.isEmpty()) return -1;
        String name0 = halfWidth(name); // 全角数字「庆余年２」先归一，不然 \d 认不出来
        Matcher matcher = SERIAL_CN.matcher(name0);
        if (matcher.find()) {
            int value = cnToInt(matcher.group(1));
            if (value > 0) return value;
        }
        matcher = SERIAL_EN.matcher(name0);
        if (matcher.find()) {
            try {
                int value = Integer.parseInt(matcher.group(1));
                if (value > 0) return value;
            } catch (NumberFormatException ignored) {
                // 数字离谱，当没写
            }
        }
        matcher = SERIAL_ROMAN.matcher(name0);
        if (matcher.find()) {
            String text = matcher.group();
            for (int i = 0; i < ROMAN.length; i++) if (ROMAN[i].equals(text)) return i + 1;
        }
        // 偷懒写法「庆余年2」：名字最后光秃秃跟着个数字也算季号（1~20）。
        // ⚠️ 前面是「集/章/话」的不算（那是集数）；「请回答1988」这种四位数、以及跟在字母后面的（S2 交给上面英文那条）都不算。
        matcher = SERIAL_TAIL.matcher(clean(name0));
        if (matcher.find()) {
            try {
                int value = Integer.parseInt(matcher.group(1));
                if (value > 0 && value <= 20) return value;
            } catch (NumberFormatException ignored) {
                // 数字离谱，当没写
            }
        }
        return -1;
    }

    /** 全角数字/空格转半角：站源爱写「庆余年２」，不归一化的话正则里的 \d 认不出来 */
    private static String halfWidth(String text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '０' && c <= '９') sb.append((char) ('0' + (c - '０')));
            else sb.append(c);
        }
        return sb.toString();
    }

    /** 名字里明写着第几季/第几部 */
    public static boolean hasSerialMark(String name) {
        return serialOf(name) > 0;
    }

    /** 系列主标题：去掉季号和噪声之后剩下的名字，同一个系列应当抠出同一个名字 */
    public static String serialMainOf(String name) {
        if (name == null || name.isEmpty()) return "";
        String text = clean(name);
        text = SERIAL_SUFFIX.matcher(text).replaceAll("");
        text = SERIAL_CN.matcher(text).replaceAll("");
        text = SERIAL_EN.matcher(text).replaceAll("");
        text = SERIAL_ROMAN.matcher(text).replaceAll("");
        return text.trim();
    }

    /** 两部剧是不是同一个系列：主标题一致；站源写法差一两个字（「权力的游戏」/「权力的游戏 第2季」）也算 */
    public static boolean sameSerial(String current, String candidate) {
        String a = serialMainOf(current);
        String b = serialMainOf(candidate);
        if (a.length() < 2 || b.length() < 2) return false;
        if (a.equals(b)) return true;
        if (a.contains(b) || b.contains(a)) return Math.abs(a.length() - b.length()) <= 1;
        return false;
    }

    /** 第 n 季的中文说法（1→一、2→二、11→十一），拼「主标题 第二季」这种搜索词用 */
    public static String cnNumber(int n) {
        if (n <= 0) return "";
        if (n < 10) return String.valueOf(CN_DIGITS[n]);
        if (n == 10) return "十";
        int tens = n / 10;
        int ones = n % 10;
        if (n < 20) return "十" + CN_DIGITS[ones];
        return (tens > 1 ? String.valueOf(CN_DIGITS[tens]) : "") + "十" + (ones > 0 ? String.valueOf(CN_DIGITS[ones]) : "");
    }

    private static final char[] CN_DIGITS = {'零', '一', '二', '三', '四', '五', '六', '七', '八', '九'};

    /** 中文数字转阿拉伯数字：支持「三」「十」「十三」「二十」，别的返回 -1 */
    private static int cnToInt(String text) {
        if (text == null || text.isEmpty()) return -1;
        if (Character.isDigit(text.charAt(0))) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        int ten = text.indexOf('十');
        if (ten < 0) return cnDigit(text);
        int tens = ten == 0 ? 1 : cnDigit(text.substring(0, ten));
        int ones = ten == text.length() - 1 ? 0 : cnDigit(text.substring(ten + 1));
        if (tens < 0 || ones < 0) return -1;
        return tens * 10 + ones;
    }

    private static int cnDigit(String text) {
        if (text == null || text.length() != 1) return -1;
        for (int i = 0; i < CN_DIGITS.length; i++) if (CN_DIGITS[i] == text.charAt(0)) return i;
        return -1;
    }

    /** 从标题（+分类名）里联想出搜索关键词：产地词 → 题材词 → 主标题 → 分类 → 兜底"短剧" */
    public static List<String> keywords(String title, String typeName, String region, int kind) {
        List<String> keys = new ArrayList<>();
        String word = regionWord(region);
        if (!word.isEmpty()) keys.add(word);
        String clean = clean(title);
        for (String tag : SORTED_TAGS) {
            if (clean.contains(tag) && !keys.contains(tag)) keys.add(tag);
            if (keys.size() >= 3) break;
        }
        String main = SERIAL_SUFFIX.matcher(clean).replaceAll("").trim();
        if (main.length() >= 2 && !main.equals(clean) && !keys.contains(main)) keys.add(main);
        if (clean.length() >= 2 && !keys.contains(clean)) keys.add(clean);
        String type = clean(typeName);
        if (!type.isEmpty() && !keys.contains(type)) keys.add(type);
        if (isComic(title) && !keys.contains("漫剧")) keys.add("漫剧"); // 只有当前这部本身就是漫剧才接着搜漫剧
        String kindWord = kindWord(kind);
        if (!kindWord.isEmpty()) {
            // 认准了剧种就按剧种兜底：刷正剧搜"电视剧"、刷漫剧搜"漫剧"、刷短剧搜"短剧"，别串
            if (!keys.contains(kindWord)) keys.add(kindWord);
        } else if (word.isEmpty() && !keys.contains("短剧")) {
            keys.add("短剧"); // 看不出剧种：还是按短剧兜底（刷外剧除外，"短剧"容易搜回国剧）
        }
        return keys;
    }

    /**
     * 判剧种：漫剧 → 短剧标记 → 单集时长 → 分类/名字里的正剧字样 → 集数，都判不出返回 UNKNOWN。
     * 顺序有讲究：**先看漫剧**（"AI漫剧"里也带"剧"字，别被后面的规则抢走），
     * 再看时长（这是最硬的证据：一集 45 分钟怎么都是正剧），最后才看分类和集数。
     */
    public static int kindOf(String name, String typeName, int episodes, long duration) {
        if (isComic(name) || isComic(typeName)) return KIND_COMIC;
        if (isAdDrama(name) || isAdDrama(typeName)) return KIND_SHORT; // 广剧是短剧的一支
        if (isShortMark(name)) return KIND_SHORT; // 名字写着短剧/微剧/竖屏/泡面番
        if (duration >= LONG_EPISODE_MS) return KIND_LONG;
        if (hasLongMark(name) || hasLongMark(typeName)) return KIND_LONG;
        if (duration > 0 && duration <= SHORT_EPISODE_MS) return KIND_SHORT;
        if (episodes >= 60) return KIND_SHORT; // 六十集往上基本是短剧的集数
        return KIND_UNKNOWN;
    }

    /** 剧种对应的兜底搜索词：刷什么就搜什么，别拿"短剧"去兜正剧 */
    public static String kindWord(int kind) {
        if (kind == KIND_COMIC) return "漫剧";
        if (kind == KIND_SHORT) return "短剧";
        if (kind == KIND_LONG) return "电视剧";
        return "";
    }

    /** 是不是广剧（短剧里按投放渠道分出来的一支） */
    public static boolean isAdDrama(String text) {
        if (text == null || text.isEmpty()) return false;
        for (String mark : AD_MARKS) if (text.contains(mark)) return true;
        return false;
    }

    /** 名字/分类上明写着正剧那一挂（对外版：验货时拿详情的分类再判一次） */
    public static boolean hasLongMark(String text) {
        if (text == null || text.isEmpty()) return false;
        for (String mark : LONG_MARKS) if (text.contains(mark)) return true;
        return false;
    }

    /** 名字上明写着短剧/漫剧，按短剧对待 */
    public static boolean isShortMark(String name) {
        if (name == null || name.isEmpty()) return false;
        for (String mark : SHORT_MARKS) if (name.contains(mark)) return true;
        return false;
    }

    /** 是不是解说/合集这类"不是一集一集"的东西 */
    public static boolean isBanned(String name) {
        if (name == null || name.isEmpty()) return true;
        for (String bad : BANNED) if (name.contains(bad)) return true;
        return false;
    }

    /** 从"更新至80集"这类备注里抠集数，抠不出来返回 0 */
    public static int parseEpisodes(String remarks) {
        if (remarks == null || remarks.isEmpty()) return 0;
        Matcher matcher = EPISODES.matcher(remarks);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (Exception ignored) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * 候选打分：关键词命中越多越像同类，命中黑名单直接判死；集数从「更新至80集」这类备注里抠。
     * `dynamicPenalty` 是动态漫的扣分，由调用方按用户画像给：
     * **没有偏好数据时扣 DYNAMIC_PENALTY（压到最低），画像显示他就爱看动态漫时不扣**（马先生的原话：
     * 不是始终最低，是没有基础数据、看不出喜好的时候才最低；一直看动态漫就直接推动态漫）。
     * 注意只降动态漫，漫剧/动漫/二次元这些不跟着降。
     */
    public static int score(String name, String remarks, List<String> keys, int dynamicPenalty) {
        if (isBanned(name)) return -100;
        int score = 0;
        for (String key : keys) if (!key.isEmpty() && name.contains(key)) score += 3;
        if (isShortMark(name)) score += 3;
        int episodes = parseEpisodes(remarks);
        if (episodes >= 40) score += 2;
        else if (episodes > 0 && episodes < 8) score -= 2;
        if (isDynamic(name)) score -= dynamicPenalty; // 只降动态漫，漫剧不降
        return score;
    }

    /**
     * 剧种得分：当前的剧种和候选对不上就扣分，对得上加分。
     * 只在**证据明确**时动手（名字上就写着"漫剧""电视剧"这种），
     * 集数、时长这类说不准的一律不动——宁可放过，也别把好剧误杀掉。
     */
    public static int kindScore(String name, int kind) {
        if (kind == KIND_UNKNOWN || name == null || name.isEmpty()) return 0;
        boolean comic = isComic(name);
        boolean shortMark = isShortMark(name);
        boolean longMark = hasLongMark(name);
        if (kind == KIND_COMIC) {
            if (comic) return 6; // 刷漫剧：还是漫剧最对味
            if (longMark) return -20; // 正剧不要
            return -8; // 真人短剧：不是不能推，排后面
        }
        if (kind == KIND_SHORT) {
            if (comic) return -12; // 刷真人短剧别跳漫剧去
            if (longMark) return -20; // 正剧不要
            if (shortMark) return 3;
            return 0;
        }
        if (kind == KIND_LONG) {
            if (comic || shortMark) return -30; // 刷正剧：短剧/漫剧一律靠后
            if (longMark) return 4; // 明写着电视剧、国产剧这类的，正对味
        }
        return 0;
    }

    /** 这个候选跟当前剧种是不是明显串味（证据确凿才判，拿不准放行） */
    public static boolean kindMismatch(String name, int kind) {
        if (kind == KIND_UNKNOWN || name == null || name.isEmpty()) return false;
        if (kind == KIND_LONG) return isComic(name) || isShortMark(name); // 刷正剧：名字带短剧/漫剧字样的不要
        if (kind == KIND_COMIC) return hasLongMark(name); // 刷漫剧：明写电视剧/网剧的不要
        if (kind == KIND_SHORT) return hasLongMark(name); // 刷短剧：明写电视剧/网剧的不要（漫剧只扣分，不硬杀）
        return false;
    }

    /**
     * 从当前剧命中的题材词里选下一部要搜的族：优先最近没刷过的族，都刷过就取第一个，
     * 一个题材词都没有返回 -1（调用方按画像/大模型兜底）。最近列表按新→旧传进来。
     */
    public static int pickFamily(List<String> tags, List<Integer> recentFamilies) {
        List<Integer> families = new ArrayList<>();
        for (String tag : tags) {
            int family = familyOf(tag);
            if (family >= 0 && !families.contains(family)) families.add(family);
        }
        if (families.isEmpty()) return -1;
        if (recentFamilies != null) for (int family : families) if (!recentFamilies.contains(family)) return family;
        return families.get(0);
    }
}
