package com.fongmi.android.tv.playback.vod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 短剧题材词库与标题小工具：判定是不是短剧、从标题里联想同类关键词、排除解说合集 */
public class BingeTags {

    /** 题材词典：命中越靠前优先级越高，长词优先匹配 */
    private static final String[] TAGS = {
        // 古装 / 宫斗 / 宅斗
        "宫斗", "宅斗", "王妃", "王爷", "太子", "公主", "长公主", "郡主", "世子", "摄政", "太后", "太妃", "娘娘", "贵妃", "冷宫", "废后", "选妃", "和亲", "替嫁", "冲喜",
        "主母", "小妾", "姨娘", "侧妃", "通房", "嫡女", "庶女", "夫人", "少奶奶", "内宅", "后宫", "朝堂", "权臣", "驸马", "皇帝", "皇叔", "国师", "太监", "宫女", "世家", "贵女",
        // 现代都市 / 婚恋
        "总裁", "霸总", "豪门", "首富", "隐婚", "闪婚", "契约婚姻", "先婚后爱", "娃娃亲", "联姻", "婚约", "前夫", "前妻", "离婚", "隐婚", "追妻", "破镜重圆",
        "替身", "白月光", "甜宠", "虐恋", "暗恋", "姐弟恋", "青梅竹马", "秘书", "保镖", "办公室", "职场", "小三", "出轨", "复仇", "逆袭",
        // 男频逆袭
        "重生", "穿越", "穿书", "系统", "签到", "金手指", "空间", "读心", "时间循环", "马甲", "隐藏身份", "扮猪吃虎",
        "战神", "龙王", "神豪", "赘婿", "上门女婿", "废柴", "废婿", "三年之期", "修仙", "仙尊", "帝尊", "师尊", "神医", "鉴宝", "天师",
        "末世", "丧尸", "抽卡", "异能", "大佬", "隐世", "退伍", "兵王", "杀手", "特工", "神医出山",
        // 家庭伦理
        "婆媳", "萌宝", "带球跑", "带娃", "萌娃", "单亲", "继母", "后妈", "重男轻女", "赡养", "二婚", "寻亲", "真假千金", "真假少爷", "弃妇", "弃子", "保姆",
        // 其他常见
        "校园", "校霸", "高考", "高三", "乡村", "种田", "致富", "悬疑", "探案", "法医", "警察", "律师", "医生", "护士", "电竞", "网红", "直播", "明星", "娱乐圈", "选秀",
        "大女主", "女强", "团宠", "马甲", "失忆", "替嫁", "守寡", "和离", "休妻", "入赘", "继妹", "闺蜜", "渣男", "凤凰男",
        // AI 漫剧 / 动态漫题材
        "漫剧", "动态漫", "沙雕漫", "二次元", "国漫", "动漫", "漫画", "漫改",
        "无限流", "副本", "规则怪谈", "诡异", "异兽", "觉醒", "升级", "卡牌", "废土", "机甲", "星际", "虫族", "兽人", "血族", "狼人", "御兽", "炼丹", "宗门", "剑尊", "魔尊", "妖妃", "狐妖"
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

    /** 低于这个集数基本是电影、单集综艺，没法一部接一部刷 */
    public static final int MIN_PLAYABLE = 8;
    /** 集数够这么多就刷，只是判据之一，不是门槛 */
    public static final int MIN_EPISODES = 20;
    /** 单集时长上限：短剧/漫剧一般 1~4 分钟，放宽到 5 分钟 */
    public static final long SHORT_EPISODE_MS = 300_000L;

    /** 能不能一部接一部刷：多集即可，短剧/漫剧优先，别卡死门槛 */
    public static boolean canBinge(int episodes, long duration, String name) {
        if (episodes < MIN_PLAYABLE) return false;
        if (episodes >= MIN_EPISODES) return true;
        if (isShortMark(name)) return true;
        return duration <= 0 || duration <= SHORT_EPISODE_MS;
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

    /** 不是一集一集正片的东西：解说、合集、混剪，一律不刷 */
    private static final String[] BANNED = {
        "解说", "速看", "几分钟", "一口气", "合集", "合辑", "盘点", "混剪", "剪辑", "cut", "CUT", "花絮", "预告", "番外", "幕后", "纯享", "加长",
        "全集版", "完整版", "抢先版", "精彩片段", "高光", "名场面", "reaction", "Reaction", "吐槽", "点评", "解析", "剧情介绍", "分集剧情", "剧情解说",
        "电影", "大电影", "剧场版", "MV", "主题曲", "插曲", "花絮合集", "幕后花絮"
    };

    /** 续集后缀，去掉后可拿主标题找同系列 */
    private static final Pattern SERIAL_SUFFIX = Pattern.compile("(第[一二三四五六七八九十\\d]+[部季篇章]|之[\\u4e00-\\u9fa5]{1,6}|续集|续|[ⅡII2]|下部|下部篇)$");

    private static final Pattern EPISODES = Pattern.compile("(\\d{1,4})\\s*[集章话]");

    private static final List<String> SORTED_TAGS = sortedTags();

    private static List<String> sortedTags() {
        List<String> tags = new ArrayList<>();
        for (int i = 0; i < TAGS.length; i++) tags.add(TAGS[i]);
        tags.sort(Comparator.comparingInt(String::length).reversed());
        return tags;
    }

    /** 洗标题：去括号、去噪声，剩下的才是能联想的部分 */
    public static String clean(String name) {
        if (name == null) return "";
        String text = name.replaceAll("[《》〈⟩\\[\\]【】()（）·・:：,，。.!！?？\"'“”‘’|/\\\\-_~\\s]", "");
        for (String noise : NOISE) text = text.replace(noise, "");
        return text.trim();
    }

    /** 从标题（+分类名）里联想出搜索关键词：产地词 → 题材词 → 主标题 → 分类 → 兜底"短剧" */
    public static List<String> keywords(String title, String typeName, String region) {
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
        if (isShortMark(title) && !keys.contains("漫剧")) keys.add("漫剧");
        if (word.isEmpty() && !keys.contains("短剧")) keys.add("短剧"); // 刷外剧就别用"短剧"兜底，容易搜回国剧
        return keys;
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

    /** 候选打分：关键词命中越多越像同类，命中黑名单直接判死 */
    public static int score(String name, List<String> keys) {
        if (isBanned(name)) return -100;
        int score = 0;
        for (String key : keys) if (!key.isEmpty() && name.contains(key)) score += 3;
        if (isShortMark(name)) score += 3;
        int episodes = parseEpisodes(name);
        if (episodes >= 40) score += 2;
        else if (episodes > 0 && episodes < 8) score -= 2;
        return score;
    }
}
