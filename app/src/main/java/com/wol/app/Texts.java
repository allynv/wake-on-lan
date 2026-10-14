package com.wol.app;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 通用小工具：时间文案、整数解析、范围收敛。
 *
 * <p>纯 Java、不依赖 Android，可在 JVM 上单测（见 tools/jvmtest/FeishuTextsTest.java）。
 * 这三件事原先在 Prefs / FeishuPrefs / MainActivity / FeishuSettingsActivity / FeishuPollService
 * 里各写了一份，现在统一到这里。
 */
public final class Texts {

    /** 时间戳显示不出来时用的占位符。 */
    public static final String EMPTY_TIME = "—";

    private Texts() {
    }

    /** 时间戳 → "HH:mm:ss"；0 或负数 → {@link #EMPTY_TIME}。 */
    public static String clock(long at) {
        if (at <= 0L) {
            return EMPTY_TIME;
        }
        return new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(at));
    }

    /** 解析整数并限制在 [min, max]；为空、非法或越界时返回 fallback。 */
    public static int parseIntOr(String text, int fallback, int min, int max) {
        if (text == null || text.trim().length() == 0) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(text.trim());
            if (value < min || value > max) {
                return fallback;
            }
            return value;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 把 value 收敛到 [min, max]。 */
    public static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        return value > max ? max : value;
    }
}
