package com.wol.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 飞书远程唤醒的配置界面。 */
public class FeishuSettingsActivity extends Activity {

    private static final int REQ_NOTIFICATION = 1001;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private FeishuPrefs feishu;
    private EditText inputAppId;
    private EditText inputAppSecret;
    private EditText inputMobile;
    private EditText inputPollSeconds;
    private EditText inputAllowed;
    private CheckBox checkEnable;
    private CheckBox checkKeepAwake;
    private TextView textChatId;
    private TextView textOpenId;
    private TextView textStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_feishu);
        feishu = new FeishuPrefs(this);

        inputAppId = (EditText) findViewById(R.id.inputAppId);
        inputAppSecret = (EditText) findViewById(R.id.inputAppSecret);
        inputMobile = (EditText) findViewById(R.id.inputMobile);
        inputPollSeconds = (EditText) findViewById(R.id.inputPollSeconds);
        inputAllowed = (EditText) findViewById(R.id.inputAllowed);
        checkEnable = (CheckBox) findViewById(R.id.feishuEnable);
        checkKeepAwake = (CheckBox) findViewById(R.id.checkKeepAwake);
        textChatId = (TextView) findViewById(R.id.textChatId);
        textOpenId = (TextView) findViewById(R.id.textOpenId);
        textStatus = (TextView) findViewById(R.id.textStatus);

        fillFromPrefs();

        TextView btnBack = (TextView) findViewById(R.id.btnBack);
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        TextView btnSave = (TextView) findViewById(R.id.btnSave);
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                readIntoPrefs();
                applyAndSave(true);
            }
        });
        TextView btnConnect = (TextView) findViewById(R.id.btnConnect);
        btnConnect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                connect();
            }
        });
        TextView btnTest = (TextView) findViewById(R.id.btnTest);
        btnTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendTest();
            }
        });
        TextView btnBattery = (TextView) findViewById(R.id.btnBattery);
        btnBattery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showBatteryDialog();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    // ------------------------------------------------------------------ 界面

    private void fillFromPrefs() {
        feishu = new FeishuPrefs(this);
        inputAppId.setText(feishu.appId);
        inputAppSecret.setText(feishu.appSecret);
        inputMobile.setText(feishu.mobile);
        inputPollSeconds.setText(String.valueOf(feishu.pollSeconds));
        inputAllowed.setText(feishu.allowedOpenIds);
        checkEnable.setChecked(feishu.enabled);
        checkKeepAwake.setChecked(feishu.keepAwake);
        textChatId.setText(getString(R.string.feishu_chat_id,
                feishu.chatId.length() == 0 ? "—" : feishu.chatId));
        textOpenId.setText(getString(R.string.feishu_open_id,
                feishu.openId.length() == 0 ? "—" : feishu.openId));
        refreshStatus();
    }

    private void refreshStatus() {
        if (textStatus == null) {
            return;
        }
        textStatus.setText(FeishuStatus.line(this, new FeishuPrefs(this)));
    }

    /** 把界面上的值读进内存中的 prefs（不落盘）。 */
    private void readIntoPrefs() {
        feishu.appId = inputAppId.getText().toString().trim();
        feishu.appSecret = inputAppSecret.getText().toString().trim();
        feishu.mobile = inputMobile.getText().toString().trim();
        feishu.allowedOpenIds = inputAllowed.getText().toString().trim();
        feishu.pollSeconds = Texts.parseIntOr(inputPollSeconds.getText().toString(),
                10, FeishuPrefs.MIN_POLL_SECONDS, FeishuPrefs.MAX_POLL_SECONDS);
        feishu.enabled = checkEnable.isChecked();
        feishu.keepAwake = checkKeepAwake.isChecked();
    }

    private void applyAndSave(boolean toast) {
        feishu.save();
        if (feishu.enabled && feishu.isConfigured()) {
            FeishuPollService.start(this);
            requestNotificationPermissionIfNeeded();
        } else {
            FeishuPollService.stop(this);
        }
        if (toast) {
            Toast.makeText(this, R.string.feishu_saved, Toast.LENGTH_SHORT).show();
        }
        textChatId.setText(getString(R.string.feishu_chat_id,
                feishu.chatId.length() == 0 ? "—" : feishu.chatId));
        textOpenId.setText(getString(R.string.feishu_open_id,
                feishu.openId.length() == 0 ? "—" : feishu.openId));
        refreshStatus();
    }

    // ------------------------------------------------------------------ 连接（取 open_id 与 chat_id）

    private void connect() {
        readIntoPrefs();
        if (feishu.appId.length() == 0 || feishu.appSecret.length() == 0 || feishu.mobile.length() == 0) {
            Toast.makeText(this, R.string.feishu_need_config, Toast.LENGTH_LONG).show();
            return;
        }
        final String base = feishu.baseUrl;
        final String appId = feishu.appId;
        final String appSecret = feishu.appSecret;
        final String mobile = feishu.mobile;
        Toast.makeText(this, R.string.feishu_connecting, Toast.LENGTH_SHORT).show();

        executor.execute(new Runnable() {
            @Override
            public void run() {
                String error = null;
                String openId = null;
                String chatId = null;
                try {
                    FeishuApi.Token token = FeishuApi.fetchToken(base, appId, appSecret);
                    openId = FeishuApi.resolveOpenId(base, token.value, mobile, null);
                    String hello = "🔗 飞书远程唤醒已连接\n"
                            + "发送 /wake 设备名 唤醒电脑，/list 查看设备，/help 查看帮助";
                    chatId = FeishuApi.sendTextToOpenId(base, token.value, openId, hello);
                    if (chatId == null || chatId.length() == 0) {
                        error = "没有取到 chat_id（飞书响应里缺少 data.chat_id）";
                    }
                } catch (FeishuApi.ApiException e) {
                    error = e.describe();
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
                }

                final String fOpenId = openId;
                final String fChatId = chatId;
                final String fError = error;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (fError != null) {
                            Toast.makeText(FeishuSettingsActivity.this,
                                    getString(R.string.feishu_error, fError), Toast.LENGTH_LONG).show();
                            return;
                        }
                        feishu.openId = fOpenId;
                        feishu.chatId = fChatId;
                        if (feishu.allowedOpenIds.length() == 0) {
                            feishu.allowedOpenIds = fOpenId;
                            inputAllowed.setText(fOpenId);
                        }
                        // 从"现在"开始，不回放历史消息
                        feishu.cursorSec = System.currentTimeMillis() / 1000L;
                        feishu.lastError = "";
                        checkEnable.setChecked(true);
                        feishu.enabled = true;
                        applyAndSave(false);
                        new AlertDialog.Builder(FeishuSettingsActivity.this)
                                .setTitle(R.string.feishu_title)
                                .setMessage(R.string.feishu_connect_ok)
                                .setPositiveButton(android.R.string.ok, null)
                                .show();
                        showBatteryDialog();
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ 测试发送

    private void sendTest() {
        readIntoPrefs();
        if (feishu.chatId.length() == 0) {
            Toast.makeText(this, R.string.feishu_need_chat, Toast.LENGTH_LONG).show();
            return;
        }
        feishu.save();
        final String base = feishu.baseUrl;
        final String appId = feishu.appId;
        final String appSecret = feishu.appSecret;
        final String chatId = feishu.chatId;

        executor.execute(new Runnable() {
            @Override
            public void run() {
                String error = null;
                try {
                    FeishuApi.Token token = FeishuApi.fetchToken(base, appId, appSecret);
                    FeishuApi.sendTextToChat(base, token.value, chatId,
                            "🔔 测试消息（" + Texts.clock(System.currentTimeMillis()) + "）");
                } catch (FeishuApi.ApiException e) {
                    error = e.describe();
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String fError = error;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (fError != null) {
                            Toast.makeText(FeishuSettingsActivity.this,
                                    getString(R.string.feishu_error, fError), Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(FeishuSettingsActivity.this,
                                    R.string.feishu_test_sent, Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ 其它

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                requestPermissions(new String[] { "android.permission.POST_NOTIFICATIONS" }, REQ_NOTIFICATION);
            } catch (Exception ignored) {
                // 被拒也不影响服务运行，只是通知不显示
            }
        }
    }

    private void showBatteryDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.feishu_battery_title)
                .setMessage(R.string.feishu_battery_msg)
                .setPositiveButton(R.string.feishu_battery_open, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        openSystemSettings();
                    }
                })
                .setNegativeButton(R.string.feishu_battery_later, null)
                .show();
    }

    /** 打开系统里本应用的详情页（省电策略 / 自启动一般都在这一层）。 */
    private void openSystemSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception ignored) {
                // 忽略
            }
        }
    }
}
