package com.fongmi.android.tv.cast;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 局域网广播用的超小打包格式：一行一个 `键=值`，键和值都过一遍 URL 编码。
 *
 * 为什么不直接用 JSON：局域网里每秒一条心跳、扫描时更是上千个包都要来回解析，
 * 而 JSON 为了这点可读性要多背一个依赖，还容易因为字符串里出现换行、引号之类的东西
 * 在两头的实现上不一致出错；自己定规则的好处是*固定不乱变*、没有转义死角、
 * 坏包解析不出来直接丢掉，不会连带把正常包也给坑了。
 *
 * 编码规则（v1 起固定不变，将来要改必须升版本号）：
 *   包 = 若干行，行与行用 \n 分隔；
 *   每行 = 键 + '=' + 值，值里出现 URL 需要转义的字符（\n、=、&、%、空格等）全部 URL 编码；
 *   键只允许字母数字下划线，碰到稀奇古怪的键直接跳过这行。
 */
final class Wire {

    private Wire() {
    }

    static String encode(Map<String, String> items) {
        StringBuilder sb = new StringBuilder(256);
        for (Map.Entry<String, String> entry : items.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isEmpty()) continue;
            String value = entry.getValue() == null ? "" : entry.getValue();
            if (sb.length() > 0) sb.append('\n');
            sb.append(key).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    static Map<String, String> decode(String text) {
        Map<String, String> items = new LinkedHashMap<>();
        if (text == null) return items;
        for (String line : text.split("\n", -1)) {
            int index = line.indexOf('=');
            if (index <= 0) continue;
            String key = line.substring(0, index);
            if (!isKey(key)) continue;
            try {
                items.put(key, URLDecoder.decode(line.substring(index + 1), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ignored) {
                // 单个字段坏了不影响别的字段：这条 put 不成就是了（比如对面编码了半个 %）
            }
        }
        return items;
    }

    private static boolean isKey(String key) {
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c >= 'a' && c <= 'z') continue;
            if (c >= 'A' && c <= 'Z') continue;
            if (c >= '0' && c <= '9') continue;
            if (c == '_') continue;
            return false;
        }
        return true;
    }
}
