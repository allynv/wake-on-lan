package com.wol.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 主界面：设备列表 + 一键唤醒。
 *
 * <p>本应用<b>不做任何在线检测</b>：点一下卡片就按用户填写的参数把标准 WOL 魔术包发出去，
 * MAC / IP / 端口是否正确由用户自己核对。
 */
public class MainActivity extends Activity {

    private static final int REQ_EDIT = 1001;
    private static final long STATUS_REFRESH_MS = 3000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final List<Device> devices = new ArrayList<Device>();

    private DeviceStore store;
    private Prefs prefs;
    private DeviceAdapter adapter;
    private ListView listView;
    private TextView emptyView;
    private TextView statusWifi;
    private TextView statusIp;
    private TextView statusBroadcast;
    private TextView statusFeishu;

    private final Runnable statusTicker = new Runnable() {
        @Override
        public void run() {
            updateStatus();
            handler.postDelayed(this, STATUS_REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        store = new DeviceStore(this);
        prefs = new Prefs(this);

        listView = (ListView) findViewById(R.id.deviceList);
        emptyView = (TextView) findViewById(R.id.emptyView);
        statusWifi = (TextView) findViewById(R.id.statusWifi);
        statusIp = (TextView) findViewById(R.id.statusIp);
        statusBroadcast = (TextView) findViewById(R.id.statusBroadcast);
        statusFeishu = (TextView) findViewById(R.id.statusFeishu);
        statusFeishu.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openFeishuSettings();
            }
        });

        adapter = new DeviceAdapter(this, devices);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                // 点一下 = 立刻发一个魔术包
                sendWake(devices.get(position));
            }
        });
        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                showDeviceMenu(devices.get(position));
                return true;
            }
        });

        ImageButton btnAdd = (ImageButton) findViewById(R.id.btnAdd);
        btnAdd.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, DeviceEditActivity.class);
                startActivityForResult(intent, REQ_EDIT);
            }
        });

        TextView btnSettings = (TextView) findViewById(R.id.btnSettings);
        btnSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSettingsDialog();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadDevices();
        ensureFeishuService();
        updateStatus();
        handler.removeCallbacks(statusTicker);
        handler.postDelayed(statusTicker, STATUS_REFRESH_MS);
    }

    /**
     * 打开 App 时确保飞书轮询服务在跑。
     *
     * <p>安装更新、被系统清理、或用户手动停止之后，服务不会自己回来；这里是最后一道自愈兜底。
     * 重复调用是安全的：服务内部会判断工作线程是否已存在。
     */
    private void ensureFeishuService() {
        FeishuPrefs feishu = new FeishuPrefs(this);
        if (feishu.enabled && feishu.isConfigured()) {
            FeishuPollService.start(this);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(statusTicker);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_EDIT) {
            reloadDevices();
        }
    }

    // ------------------------------------------------------------------ 列表

    private void reloadDevices() {
        devices.clear();
        devices.addAll(store.load());
        adapter.notifyDataSetChanged();
        emptyView.setVisibility(devices.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void showDeviceMenu(final Device device) {
        final String[] items = new String[] {
                getString(R.string.menu_edit),
                getString(R.string.menu_copy_mac),
                getString(R.string.menu_delete)
        };
        new AlertDialog.Builder(this)
                .setTitle(getString(R.string.menu_title, device.name))
                .setItems(items, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        switch (which) {
                            case 0:
                                editDevice(device);
                                break;
                            case 1:
                                copyMac(device);
                                break;
                            default:
                                confirmDelete(device);
                                break;
                        }
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void editDevice(Device device) {
        Intent intent = new Intent(this, DeviceEditActivity.class);
        intent.putExtra(DeviceEditActivity.EXTRA_ID, device.id);
        startActivityForResult(intent, REQ_EDIT);
    }

    private void copyMac(Device device) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("MAC", device.mac));
            Toast.makeText(this, getString(R.string.copied, device.mac), Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmDelete(final Device device) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_confirm_title)
                .setMessage(getString(R.string.delete_confirm_msg, device.name))
                .setPositiveButton(R.string.menu_delete, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        store.delete(device.id);
                        reloadDevices();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ 唤醒

    /** 立即发一个唤醒包 —— 唯一的动作，没有检测、没有前置判断。 */
    private void sendWake(final Device device) {
        Toast.makeText(this, R.string.sending, Toast.LENGTH_SHORT).show();
        final Context appContext = getApplicationContext();
        final Prefs snapshot = prefs;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                final WolSender.Result result = WolSender.send(appContext, device, snapshot);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        onWakeResult(result);
                    }
                });
            }
        });
    }

    private void onWakeResult(WolSender.Result result) {
        if (!result.ok) {
            Toast.makeText(this, getString(R.string.wake_failed, result.error), Toast.LENGTH_LONG).show();
            return;
        }
        String message = getString(R.string.wake_done, result.packets,
                result.targetText() + " :" + result.portText);
        if (result.fallbackFrom.length() > 0) {
            message = message + "\n" + getString(R.string.wake_arp_fallback,
                    result.fallbackFrom, result.fallbackTo);
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------------ 状态条

    private void updateStatus() {
        WolSender.WifiInfo info = WolSender.getWifiInfo(this);
        if (info.found) {
            statusWifi.setText(getString(R.string.status_wifi_connected,
                    info.ifName.length() > 0 ? info.ifName : "wlan0"));
            statusWifi.setTextColor(getColor(R.color.text_primary));
            statusIp.setText(getString(R.string.status_local_ip, info.ip, Integer.valueOf(info.prefix)));
            if (info.broadcast.length() > 0) {
                statusBroadcast.setVisibility(View.VISIBLE);
                statusBroadcast.setText(getString(R.string.status_broadcast, info.broadcast));
            } else {
                statusBroadcast.setVisibility(View.GONE);
            }
        } else {
            statusWifi.setText(R.string.status_no_wifi);
            statusWifi.setTextColor(getColor(R.color.danger));
            statusIp.setText(R.string.status_no_ip);
            statusBroadcast.setVisibility(View.GONE);
        }
        updateFeishuStatus();
    }

    /** 底部飞书远程唤醒状态行（未启用时隐藏）；点一下进入配置页。 */
    private void updateFeishuStatus() {
        FeishuPrefs feishu = new FeishuPrefs(this);
        if (!feishu.enabled || !feishu.isConfigured()) {
            statusFeishu.setVisibility(View.GONE);
            return;
        }
        statusFeishu.setVisibility(View.VISIBLE);
        statusFeishu.setTextColor(getColor(FeishuStatus.hasError(feishu)
                ? R.color.danger : R.color.text_secondary));
        statusFeishu.setText(FeishuStatus.line(this, feishu));
    }

    private void openFeishuSettings() {
        startActivity(new Intent(this, FeishuSettingsActivity.class));
    }

    // ------------------------------------------------------------------ 设置

    private void showSettingsDialog() {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null, false);
        final EditText inputPort = (EditText) content.findViewById(R.id.setPort);
        final EditText inputRepeat = (EditText) content.findViewById(R.id.setRepeat);
        final CheckBox port7 = (CheckBox) content.findViewById(R.id.setPort7);

        inputPort.setText(String.valueOf(prefs.defaultPort));
        inputRepeat.setText(String.valueOf(prefs.repeat));
        port7.setChecked(prefs.alsoPort7);

        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_title)
                .setView(content)
                .setPositiveButton(R.string.action_save, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        prefs.defaultPort = Texts.parseIntOr(inputPort.getText().toString(), 9, 1, 65535);
                        prefs.repeat = Texts.parseIntOr(inputRepeat.getText().toString(), 1, 1, 10);
                        prefs.alsoPort7 = port7.isChecked();
                        prefs.save();
                        Toast.makeText(MainActivity.this, R.string.settings_saved, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .setNeutralButton(R.string.feishu_entry, new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        openFeishuSettings();
                    }
                })
                .show();
    }
}
