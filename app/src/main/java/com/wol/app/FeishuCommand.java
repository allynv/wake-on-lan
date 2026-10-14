package com.wol.app;

import java.util.ArrayList;
import java.util.List;

/**
 * 飞书指令解析与设备匹配。
 *
 * <p>纯 Java、无 Android 依赖，可在 JVM 上直接单测（见 tools/jvmtest/FeishuCommandTest.java）。
 */
public final class FeishuCommand {

    /** 不是指令：静默忽略，不回复。 */
    public static final int TYPE_NONE = 0;
    /** /wake 设备名。 */
    public static final int TYPE_WAKE = 1;
    /** /list：列出设备。 */
    public static final int TYPE_LIST = 2;
    /** /help：帮助（也包括"前缀命中了但动词不认识"的情况）。 */
    public static final int TYPE_HELP = 3;

    /** 没有匹配到任何设备。 */
    public static final int MATCH_NONE = -1;
    /** 匹配到多个设备，需要写得更精确（或用序号）。 */
    public static final int MATCH_AMBIGUOUS = -2;

    public final int type;
    public final String arg;

    private FeishuCommand(int type, String arg) {
        this.type = type;
        this.arg = arg == null ? "" : arg;
    }

    /**
     * 解析一条文本消息。
     *
     * @param text   消息原文
     * @param prefix 指令前缀（默认 "/"）；传空串表示不要求前缀，此时第一段必须是已知动词，
     *               并且仍然兼容带 "/" 的写法
     */
    public static FeishuCommand parse(String text, String prefix) {
        if (text == null) {
            return new FeishuCommand(TYPE_NONE, "");
        }
        String body = text.trim();
        String p = prefix == null ? "" : prefix.trim();

        if (p.length() > 0) {
            if (!body.startsWith(p)) {
                return new FeishuCommand(TYPE_NONE, "");
            }
            body = body.substring(p.length()).trim();
        } else if (body.startsWith("/")) {
            // 未要求前缀时也允许用户习惯性地带上 "/"
            body = body.substring(1).trim();
        }
        if (body.length() == 0) {
            return new FeishuCommand(TYPE_HELP, "");
        }

        String verb;
        String rest;
        int split = firstWhitespace(body);
        if (split < 0) {
            verb = body;
            rest = "";
        } else {
            verb = body.substring(0, split);
            rest = body.substring(split + 1).trim();
        }

        String lower = verb.toLowerCase();
        if ("wake".equals(lower) || "唤醒".equals(verb) || "叫醒".equals(verb) || "开机".equals(verb)) {
            return new FeishuCommand(TYPE_WAKE, rest);
        }
        if ("list".equals(lower) || "ls".equals(lower) || "设备".equals(verb) || "列表".equals(verb)) {
            return new FeishuCommand(TYPE_LIST, rest);
        }
        if ("help".equals(lower) || "?".equals(lower) || "？".equals(verb)
                || "帮助".equals(verb) || "指令".equals(verb)) {
            return new FeishuCommand(TYPE_HELP, rest);
        }
        // 带了前缀却写了个不认识的动词：说明用户是想操作，回帮助而不是沉默
        return p.length() > 0 ? new FeishuCommand(TYPE_HELP, "") : new FeishuCommand(TYPE_NONE, "");
    }

    /**
     * 在设备名列表里定位设备。
     *
     * <p>匹配顺序：完全相等 → 忽略大小写相等 → 列表序号（1 起）→ 名称包含（唯一命中才算）。
     *
     * @return 索引；{@link #MATCH_NONE} 没找到；{@link #MATCH_AMBIGUOUS} 命中多个
     */
    public static int matchDevice(String arg, List<String> names) {
        if (arg == null || names == null || names.isEmpty()) {
            return MATCH_NONE;
        }
        String key = arg.trim();
        if (key.length() == 0) {
            return MATCH_NONE;
        }
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i) != null && names.get(i).trim().equals(key)) {
                return i;
            }
        }
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i) != null && names.get(i).trim().equalsIgnoreCase(key)) {
                return i;
            }
        }
        try {
            int index = Integer.parseInt(key) - 1;
            if (index >= 0 && index < names.size()) {
                return index;
            }
        } catch (NumberFormatException ignored) {
            // 不是序号，继续按名称包含匹配
        }
        List<Integer> hits = listMatches(arg, names);
        if (hits.size() == 1) {
            return hits.get(0).intValue();
        }
        return hits.size() > 1 ? MATCH_AMBIGUOUS : MATCH_NONE;
    }

    /** 所有"名称包含该关键字"的设备索引，用于歧义时提示候选。 */
    public static List<Integer> listMatches(String arg, List<String> names) {
        List<Integer> hits = new ArrayList<Integer>();
        if (arg == null || names == null) {
            return hits;
        }
        String key = arg.trim().toLowerCase();
        if (key.length() == 0) {
            return hits;
        }
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            if (name != null && name.toLowerCase().contains(key)) {
                hits.add(Integer.valueOf(i));
            }
        }
        return hits;
    }

    /** 发送者白名单：名单为空表示不校验（由飞书应用可用范围兜底）。 */
    public static boolean isAllowed(String senderId, String allowList) {
        if (allowList == null || allowList.trim().length() == 0) {
            return true;
        }
        if (senderId == null) {
            return false;
        }
        String target = senderId.trim().toLowerCase();
        String[] parts = allowList.split("[,，;\\s]+");
        for (String part : parts) {
            if (part != null && part.trim().toLowerCase().equals(target)) {
                return true;
            }
        }
        return false;
    }

    private static int firstWhitespace(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u3000') {
                return i;
            }
        }
        return -1;
    }
}
