package com.wol.app;

import android.content.Context;

/**
 * 飞书远程唤醒的状态文案。
 *
 * <p>主界面底部状态行与设置页原先各写了一份同样的拼装逻辑，这里合成一处。
 */
public final class FeishuStatus {

    private FeishuStatus() {
    }

    /** 最近一次轮询是否出错（出错时界面用告警色显示）。 */
    public static boolean hasError(FeishuPrefs prefs) {
        return prefs.lastError != null && prefs.lastError.length() > 0;
    }

    /** 完整状态行文案（未启用时返回「运行状态：未启用」）。 */
    public static String line(Context context, FeishuPrefs prefs) {
        if (!prefs.enabled) {
            return context.getString(R.string.feishu_status,
                    context.getString(R.string.feishu_mode_off));
        }
        if (hasError(prefs)) {
            return context.getString(R.string.feishu_status,
                    context.getString(R.string.feishu_mode_err, prefs.lastError));
        }
        return context.getString(R.string.feishu_status,
                context.getString(R.string.feishu_mode_on,
                        Texts.clock(prefs.lastCheckAt), Texts.clock(prefs.lastWakeAt)));
    }
}
