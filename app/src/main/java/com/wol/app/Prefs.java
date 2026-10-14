package com.wol.app;

import android.content.Context;
import android.content.SharedPreferences;

/** 全局设置。 */
public class Prefs {

    private static final String PREF_NAME = "wol_settings";
    private static final String KEY_PORT = "defaultPort";
    private static final String KEY_REPEAT = "repeat";
    private static final String KEY_PORT7 = "alsoPort7";

    /** 默认唤醒端口。 */
    public int defaultPort = 9;
    /** 每个目标重复发送次数（默认 1，即每个目标只发一个魔术包）。 */
    public int repeat = 1;
    /** 是否同时向端口 7 发送（兼容老网卡）。 */
    public boolean alsoPort7 = false;

    private final SharedPreferences sp;

    public Prefs(Context context) {
        this.sp = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        load();
    }

    public void load() {
        defaultPort = Texts.clamp(sp.getInt(KEY_PORT, 9), 1, 65535);
        repeat = Texts.clamp(sp.getInt(KEY_REPEAT, 1), 1, 10);
        alsoPort7 = sp.getBoolean(KEY_PORT7, false);
    }

    public void save() {
        sp.edit()
                .putInt(KEY_PORT, defaultPort)
                .putInt(KEY_REPEAT, repeat)
                .putBoolean(KEY_PORT7, alsoPort7)
                .apply();
    }
}
