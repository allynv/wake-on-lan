package com.wol.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 飞书远程唤醒的前台服务：轮询机器人单聊里的指令，命中后按已配置的设备参数发 WOL 魔术包并回复结果。
 *
 * <p>为什么用轮询：飞书在公网、家里没有公网 IP，事件订阅/长连接在 Android 上不可行；
 * 而「获取会话历史消息」接口限频 1000 次/分钟，10 秒一次（6 次/分钟）没有任何压力。
 */
public class FeishuPollService extends Service {

    private static final String TAG = "FeishuPoll";
    public static final String ACTION_STOP = "com.wol.app.action.FEISHU_STOP";

    private static final String CHANNEL_ID = "feishu_poll_service";
    /**
     * 历史通道：{@code feishu_poll}（每轮刷新文案）、{@code feishu_poll_v2}（重要级别被压到最低，
     * 结果连出错提示都看不见了）。启动后一并删掉，避免系统设置里堆着同名通知。
     */
    private static final String[] LEGACY_CHANNELS = { "feishu_poll", "feishu_poll_v2" };
    private static final int NOTIFICATION_ID = 0x5701;
    /** 停摆超过这个时长就不再补处理历史指令（防止一开机重放一堆旧指令）。 */
    private static final long CATCHUP_LIMIT_SEC = 600L;
    /** 「需要用户处理」那类错误的最短提醒间隔（临时故障根本不提醒）。 */
    private static final long ACTIONABLE_REPLY_INTERVAL_MS = 10 * 60 * 1000L;
    /** 连续失败超过这个时长，才提醒一次「手机连不上飞书」。 */
    private static final long OUTAGE_NOTIFY_MS = 15 * 60 * 1000L;
    /** 通知文案最短刷新间隔：状态来回跳变时也不至于反复弹通知。 */
    private static final long NOTIFICATION_MIN_GAP_MS = 30 * 1000L;

    private volatile boolean running;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;

    private FeishuPrefs feishu;
    private Prefs prefs;
    private DeviceStore store;

    private FeishuApi.Token token;
    private int consecutiveFailures;
    private final Set<String> recentIds = new HashSet<String>();

    private String lastErrorKey = "";
    private long lastErrorAt;
    /** 本轮连续失败开始的时间（0 表示当前正常）。 */
    private long outageStartedAt;
    /** 本轮长时间离线是否已经提醒过。 */
    private boolean outageReported;
    private String lastNotificationText = "";
    private long lastNotificationAt;

    // ------------------------------------------------------------------ 生命周期

    public static void start(Context context) {
        Intent intent = new Intent(context, FeishuPollService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, FeishuPollService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        store = new DeviceStore(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        feishu = new FeishuPrefs(this);
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!feishu.enabled || !feishu.isConfigured()) {
            Log.i(TAG, "未启用或配置不完整，服务退出");
            stopSelf();
            return START_NOT_STICKY;
        }
        String idle = idleText();
        lastNotificationText = idle;
        lastNotificationAt = System.currentTimeMillis();
        startForeground(NOTIFICATION_ID, buildNotification(idle));
        startWorker();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        releaseWakeLock();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 轮询主循环

    private void startWorker() {
        if (worker != null && worker.isAlive()) {
            return;
        }
        running = true;
        acquireWakeLock();
        recentIds.clear();
        List<String> saved = feishu.recentIds();
        for (int i = 0; i < saved.size(); i++) {
            recentIds.add(saved.get(i));
        }
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "feishu-poll");
        worker.start();
    }

    private void loop() {
        boolean firstRound = true;
        while (running) {
            long sleepMs = Math.max(FeishuPrefs.MIN_POLL_SECONDS, feishu.pollSeconds) * 1000L;
            try {
                feishu = new FeishuPrefs(this);
                if (!feishu.enabled || !feishu.isConfigured()) {
                    break;
                }
                if (firstRound) {
                    alignCursorOnStart();
                    firstRound = false;
                }
                ensureToken();
                handleMessages(listMessagesWithRetry());
                onPollSuccess();
                // 轮询成功不再刷新通知：空闲文案不带时间戳，平时通知栏纹丝不动
                updateNotificationIfChanged(idleText(), false);
            } catch (FeishuApi.ApiException e) {
                token = null;
                handleFailure(e.code, e.describe());
                sleepMs = backoff(sleepMs);
            } catch (Exception e) {
                handleFailure(-1, e.getMessage() == null ? e.toString() : e.getMessage());
                sleepMs = backoff(sleepMs);
            }
            sleep(sleepMs);
        }
        if (running) {
            // 配置被关闭：主动结束服务
            stopSelf();
        }
    }

    /**
     * 对齐游标，避免重放历史指令：
     * 全新启用 → 从"现在"开始；停摆过久 → 只补最近 10 分钟。
     */
    private void alignCursorOnStart() {
        long now = System.currentTimeMillis() / 1000L;
        if (feishu.cursorSec <= 0L) {
            feishu.cursorSec = now;
            feishu.save();
            Log.i(TAG, "首次启动，游标对齐到当前时间");
        } else if (feishu.cursorSec < now - CATCHUP_LIMIT_SEC) {
            long skippedSec = now - feishu.cursorSec;
            feishu.cursorSec = now - CATCHUP_LIMIT_SEC;
            feishu.lastError = "已跳过 " + (skippedSec / 60) + " 分钟内的历史指令";
            feishu.save();
            Log.i(TAG, "停摆过久，跳过历史指令");
        }
    }

    private void ensureToken() throws FeishuApi.ApiException {
        if (token != null && token.value.length() > 0 && System.currentTimeMillis() < token.expireAtMs) {
            return;
        }
        token = FeishuApi.fetchToken(feishu.baseUrl, feishu.appId, feishu.appSecret);
    }

    private void handleMessages(List<FeishuApi.Message> messages) {
        if (messages.isEmpty()) {
            return;
        }
        Collections.sort(messages, new Comparator<FeishuApi.Message>() {
            @Override
            public int compare(FeishuApi.Message a, FeishuApi.Message b) {
                if (a.createTimeMs == b.createTimeMs) {
                    return a.messageId.compareTo(b.messageId);
                }
                return a.createTimeMs < b.createTimeMs ? -1 : 1;
            }
        });

        long maxSec = feishu.cursorSec;
        for (FeishuApi.Message message : messages) {
            if (message.deleted || !"text".equals(message.msgType)) {
                continue;
            }
            if ("app".equals(message.senderType)) {
                continue;   // 机器人自己发的（含我们的回复）
            }
            if (message.messageId.length() > 0 && recentIds.contains(message.messageId)) {
                continue;   // 同一秒内重复取到的消息
            }
            if (message.messageId.length() > 0) {
                recentIds.add(message.messageId);
                feishu.rememberMessageId(message.messageId);
            }
            long sec = message.createTimeMs / 1000L;
            if (sec > maxSec) {
                maxSec = sec;
            }
            if (!FeishuCommand.isAllowed(message.senderId, feishu.allowedOpenIds)) {
                Log.i(TAG, "忽略非白名单发送者：" + message.senderId);
                continue;
            }
            FeishuCommand command = FeishuCommand.parse(message.text, "/");
            if (command.type == FeishuCommand.TYPE_NONE) {
                continue;
            }
            try {
                handleCommand(command);
            } catch (FeishuApi.ApiException e) {
                Log.w(TAG, "回复失败：" + e.describe());
            } catch (Exception e) {
                Log.w(TAG, "处理指令失败", e);
            }
        }
        if (maxSec > feishu.cursorSec) {
            feishu.cursorSec = maxSec;
        }
        feishu.save();
    }

    // ------------------------------------------------------------------ 指令处理

    private void handleCommand(FeishuCommand command) throws FeishuApi.ApiException {
        if (command.type == FeishuCommand.TYPE_HELP) {
            reply(FeishuTexts.help());
            return;
        }
        List<Device> devices = store.load();
        if (command.type == FeishuCommand.TYPE_LIST) {
            reply(FeishuTexts.deviceList(devices));
            return;
        }
        // TYPE_WAKE
        if (devices.isEmpty()) {
            reply(FeishuTexts.noDevices());
            return;
        }
        if (command.arg.length() == 0) {
            reply(FeishuTexts.usage(devices));
            return;
        }
        List<String> names = new ArrayList<String>();
        for (int i = 0; i < devices.size(); i++) {
            names.add(devices.get(i).name);
        }
        int index = FeishuCommand.matchDevice(command.arg, names);
        if (index == FeishuCommand.MATCH_AMBIGUOUS) {
            reply(FeishuTexts.ambiguous(command.arg, names,
                    FeishuCommand.listMatches(command.arg, names)));
            return;
        }
        if (index < 0) {
            reply(FeishuTexts.notFound(command.arg, devices));
            return;
        }

        Device device = devices.get(index);
        WolSender.Result result = WolSender.send(getApplicationContext(), device, prefs);
        if (!result.ok) {
            reply(FeishuTexts.wakeFailed(device.name, result.error));
            return;
        }
        reply(FeishuTexts.wakeOk(toWake(device, result)));

        feishu.lastWakeAt = System.currentTimeMillis();
        feishu.lastError = "";
        feishu.save();
        updateNotificationIfChanged("✅ " + Texts.clock(feishu.lastWakeAt)
                + " 已发送唤醒包 → " + device.name, true);
    }

    /** 把发送结果映射成文案层需要的数据，让 {@link FeishuTexts} 不必依赖 Android。 */
    private static FeishuTexts.Wake toWake(Device device, WolSender.Result result) {
        FeishuTexts.Wake wake = new FeishuTexts.Wake();
        wake.packets = result.packets;
        wake.name = device.name;
        wake.mac = device.mac;
        wake.target = result.targetText();
        wake.port = result.portText;
        wake.localCidr = result.localCidr;
        wake.fallbackFrom = result.fallbackFrom;
        wake.fallbackTo = result.fallbackTo;
        return wake;
    }

    private void reply(String text) throws FeishuApi.ApiException {
        ensureToken();
        FeishuApi.sendTextToChat(feishu.baseUrl, token.value, feishu.chatId, text);
    }

    private void safeReply(String text) {
        try {
            reply(text);
        } catch (Exception e) {
            Log.w(TAG, "提示发送失败：" + e.getMessage());
        }
    }

    /**
     * 拉取消息；遇到临时故障（超时等）就地重试一次。
     *
     * <p>手机夜间息屏时 Wi-Fi 会进省电模式，第一次握手超时很常见，隔一秒重试基本就过。
     * 这样既能少报错，也能让指令响应更快（否则要等下一轮退避）。
     */
    private List<FeishuApi.Message> listMessagesWithRetry() throws FeishuApi.ApiException {
        try {
            return FeishuApi.listMessages(feishu.baseUrl, token.value, feishu.chatId, feishu.cursorSec, 20);
        } catch (FeishuApi.ApiException e) {
            if (FeishuErrors.isActionable(e.code)) {
                throw e;
            }
            Log.w(TAG, "拉取消息失败，1 秒后重试一次：" + e.describe());
            sleep(1000L);
            return FeishuApi.listMessages(feishu.baseUrl, token.value, feishu.chatId, feishu.cursorSec, 20);
        }
    }

    /** 一轮轮询成功：清空失败状态；如果之前长时间离线过，报一次「已恢复」。 */
    private void onPollSuccess() {
        consecutiveFailures = 0;
        feishu.lastCheckAt = System.currentTimeMillis();
        feishu.lastError = "";
        feishu.save();
        lastErrorKey = "";
        if (outageReported) {
            safeReply("✅ 飞书连接已恢复，可以继续发指令了。");
        }
        outageStartedAt = 0L;
        outageReported = false;
    }

    /**
     * 统一处理一轮失败。
     *
     * <p>关键区别：**临时故障只记日志、不往会话里发消息**（网络超时、飞书服务端内部错误都属于这类）；
     * 只有需要用户动手的错误（凭证 / 权限 / 应用未启用）才提醒，而且同一轮故障只提醒一次。
     * 另外无论哪种错误，只要持续超过 {@link #OUTAGE_NOTIFY_MS}，就提醒一次「手机连不上飞书」——
     * 否则你在外面发指令会以为程序失灵了。
     */
    private void handleFailure(int code, String description) {
        consecutiveFailures++;
        long now = System.currentTimeMillis();
        feishu.lastError = description;
        feishu.save();

        boolean actionable = FeishuErrors.isActionable(code);
        updateNotificationIfChanged(actionable ? "⚠️ " + description : "⚠️ 网络异常，正在重试", false);

        if (outageStartedAt == 0L) {
            outageStartedAt = now;
            outageReported = false;
        }
        if (actionable) {
            boolean sameAsLast = description.equals(lastErrorKey);
            if (!sameAsLast && now - lastErrorAt >= ACTIONABLE_REPLY_INTERVAL_MS) {
                lastErrorKey = description;
                lastErrorAt = now;
                safeReply(FeishuTexts.apiError(description));
            } else {
                lastErrorKey = description;
            }
        } else {
            Log.w(TAG, "临时故障（连续第 " + consecutiveFailures + " 次），仅重试不上报：" + description);
        }
        if (!outageReported && now - outageStartedAt >= OUTAGE_NOTIFY_MS) {
            outageReported = true;
            safeReply("📴 手机已连续 " + (OUTAGE_NOTIFY_MS / 60000) + " 分钟无法连接飞书（多为网络问题），"
                    + "这段时间的指令收不到；恢复后会自动继续。");
        }
    }

    // ------------------------------------------------------------------ 通知与电源

    /**
     * 常驻通知（前台服务必须显示，否则 Android 8+ 会判定应用异常并杀掉服务）。
     *
     * <p>为了不打扰又保留可见性：重要级别用 {@code IMPORTANCE_LOW}（有图标、不出声、不弹横幅），
     * 但文案只在状态真的变化时才更新，并且 {@code setShowWhen(false)} 去掉右上角那个一直在跳的时间。
     * 这样平时通知栏纹丝不动，出问题时又能一眼看见 ⚠️ 提示。
     */
    private Notification buildNotification(String status) {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                    NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "飞书远程唤醒",
                            NotificationManager.IMPORTANCE_LOW);
                    channel.setDescription("后台轮询飞书指令，收到指令后发送唤醒包");
                    channel.setShowBadge(false);
                    channel.enableVibration(false);
                    channel.setSound(null, null);
                    manager.createNotificationChannel(channel);
                }
                // 历史通道不再使用，删掉以免系统设置里出现多条同名通知
                for (int i = 0; i < LEGACY_CHANNELS.length; i++) {
                    manager.deleteNotificationChannel(LEGACY_CHANNELS[i]);
                }
            }
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        Intent open = new Intent(this, FeishuSettingsActivity.class);
        // API 31+ 要求显式指定可变性，FLAG_IMMUTABLE 从 API 23 起可用（minSdk 23）
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        builder.setSmallIcon(R.drawable.ic_stat_feishu)
                .setContentTitle("飞书远程唤醒")
                .setContentText(status)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(pending);
        return builder.build();
    }

    /** 空闲文案：不带时间戳，所以平时不会有任何刷新动作。 */
    private String idleText() {
        int seconds = Math.max(FeishuPrefs.MIN_POLL_SECONDS, feishu.pollSeconds);
        return "待命中 · 每 " + seconds + " 秒检查一次指令";
    }

    /**
     * 只在文案真的变化时才更新通知（非紧急状态还要求距上次刷新超过 30 秒）。
     *
     * <p>这样轮询本身不会动通知栏，只有「启动 / 唤醒成功 / 出错 / 恢复正常」才会变一次。
     */
    private void updateNotificationIfChanged(String status, boolean urgent) {
        long now = System.currentTimeMillis();
        if (status.equals(lastNotificationText)) {
            return;
        }
        if (!urgent && now - lastNotificationAt < NOTIFICATION_MIN_GAP_MS) {
            return;
        }
        lastNotificationText = status;
        lastNotificationAt = now;
        updateNotification(status);
    }

    private void updateNotification(String status) {
        try {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID, buildNotification(status));
            }
        } catch (Exception e) {
            Log.w(TAG, "更新通知失败：" + e.getMessage());
        }
    }

    private void acquireWakeLock() {
        if (!feishu.keepAwake || wakeLock != null) {
            return;
        }
        try {
            PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (power == null) {
                return;
            }
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wol:feishu-poll");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (Exception e) {
            wakeLock = null;
            Log.w(TAG, "获取唤醒锁失败：" + e.getMessage());
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Exception ignored) {
            // 忽略
        }
        wakeLock = null;
    }

    // ------------------------------------------------------------------ 工具

    private long backoff(long baseMs) {
        int shift = Math.min(4, consecutiveFailures);
        long value = baseMs * (1L << shift);
        return Math.min(value, 120000L);
    }

    private void sleep(long ms) {
        long deadline = System.currentTimeMillis() + ms;
        while (running && System.currentTimeMillis() < deadline) {
            long remaining = deadline - System.currentTimeMillis();
            try {
                Thread.sleep(Math.min(500L, Math.max(50L, remaining)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
