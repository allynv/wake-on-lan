package com.wol.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * 自动拉起飞书轮询服务。
 *
 * <p>监听两个时机：
 * <ul>
 *   <li>{@code BOOT_COMPLETED} —— 手机重启后恢复；</li>
 *   <li>{@code MY_PACKAGE_REPLACED} —— **覆盖安装新版本之后**。这个很关键：安装更新会杀掉旧进程，
 *       前台服务不会自己回来，不监听它就会出现"装完新包就再也收不到指令"。</li>
 * </ul>
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action);
        boolean replaced = Intent.ACTION_MY_PACKAGE_REPLACED.equals(action);
        if (!boot && !replaced) {
            return;
        }
        try {
            FeishuPrefs feishu = new FeishuPrefs(context);
            if (feishu.enabled && feishu.isConfigured()) {
                FeishuPollService.start(context);
                Log.i(TAG, "已启动飞书轮询服务（" + action + "）");
            }
        } catch (Exception e) {
            Log.w(TAG, "启动飞书轮询服务失败：" + e.getMessage());
        }
    }
}
