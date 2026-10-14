package com.wol.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** 飞书远程唤醒的配置与运行时状态（与唤醒本机的 {@link Prefs} 分开存放）。 */
public class FeishuPrefs {

    private static final String PREF_NAME = "wol_feishu";

    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_BASE_URL = "baseUrl";
    private static final String KEY_APP_ID = "appId";
    private static final String KEY_APP_SECRET = "appSecret";
    private static final String KEY_MOBILE = "mobile";
    private static final String KEY_OPEN_ID = "openId";
    private static final String KEY_CHAT_ID = "chatId";
    private static final String KEY_POLL_SECONDS = "pollSeconds";
    private static final String KEY_ALLOWED = "allowedOpenIds";
    private static final String KEY_KEEP_AWAKE = "keepAwake";
    private static final String KEY_CURSOR = "cursorSec";
    private static final String KEY_RECENT_IDS = "recentMessageIds";
    private static final String KEY_LAST_CHECK = "lastCheckAt";
    private static final String KEY_LAST_WAKE = "lastWakeAt";
    private static final String KEY_LAST_ERROR = "lastError";

    public static final int MIN_POLL_SECONDS = 5;
    public static final int MAX_POLL_SECONDS = 120;
    /** 最多记住多少个已处理的 message_id。 */
    private static final int MAX_RECENT_IDS = 50;

    /** 总开关。 */
    public boolean enabled;
    /** 服务地址：飞书国内版 open.feishu.cn，Lark 国际版 open.larksuite.com。 */
    public String baseUrl = FeishuApi.DEFAULT_BASE;
    public String appId = "";
    public String appSecret = "";
    /** 你自己的手机号，仅用于首次换 open_id。 */
    public String mobile = "";
    /** 首次「连接」时自动填入；非空时作为白名单依据。 */
    public String openId = "";
    /** 机器人单聊的 chat_id，首次「连接」时自动填入。 */
    public String chatId = "";
    /** 轮询间隔（秒）。 */
    public int pollSeconds = 10;
    /** 允许触发指令的 open_id 列表（逗号分隔）；为空表示不校验。 */
    public String allowedOpenIds = "";
    /** 是否持有部分唤醒锁（设备常插电时建议开）。 */
    public boolean keepAwake = true;
    /** 已处理消息的时间游标（秒），防止重放。 */
    public long cursorSec;
    /** 最近处理过的 message_id（逗号分隔）。 */
    public String recentMessageIds = "";
    public long lastCheckAt;
    public long lastWakeAt;
    public String lastError = "";

    private final SharedPreferences sp;

    public FeishuPrefs(Context context) {
        this.sp = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        load();
    }

    public void load() {
        enabled = sp.getBoolean(KEY_ENABLED, false);
        baseUrl = sp.getString(KEY_BASE_URL, FeishuApi.DEFAULT_BASE);
        appId = sp.getString(KEY_APP_ID, "");
        appSecret = sp.getString(KEY_APP_SECRET, "");
        mobile = sp.getString(KEY_MOBILE, "");
        openId = sp.getString(KEY_OPEN_ID, "");
        chatId = sp.getString(KEY_CHAT_ID, "");
        pollSeconds = Texts.clamp(sp.getInt(KEY_POLL_SECONDS, 10), MIN_POLL_SECONDS, MAX_POLL_SECONDS);
        allowedOpenIds = sp.getString(KEY_ALLOWED, "");
        keepAwake = sp.getBoolean(KEY_KEEP_AWAKE, true);
        cursorSec = sp.getLong(KEY_CURSOR, 0L);
        recentMessageIds = sp.getString(KEY_RECENT_IDS, "");
        lastCheckAt = sp.getLong(KEY_LAST_CHECK, 0L);
        lastWakeAt = sp.getLong(KEY_LAST_WAKE, 0L);
        lastError = sp.getString(KEY_LAST_ERROR, "");
    }

    public void save() {
        sp.edit()
                .putBoolean(KEY_ENABLED, enabled)
                .putString(KEY_BASE_URL, baseUrl)
                .putString(KEY_APP_ID, appId)
                .putString(KEY_APP_SECRET, appSecret)
                .putString(KEY_MOBILE, mobile)
                .putString(KEY_OPEN_ID, openId)
                .putString(KEY_CHAT_ID, chatId)
                .putInt(KEY_POLL_SECONDS, Texts.clamp(pollSeconds, MIN_POLL_SECONDS, MAX_POLL_SECONDS))
                .putString(KEY_ALLOWED, allowedOpenIds)
                .putBoolean(KEY_KEEP_AWAKE, keepAwake)
                .putLong(KEY_CURSOR, cursorSec)
                .putString(KEY_RECENT_IDS, recentMessageIds)
                .putLong(KEY_LAST_CHECK, lastCheckAt)
                .putLong(KEY_LAST_WAKE, lastWakeAt)
                .putString(KEY_LAST_ERROR, lastError)
                .apply();
    }

    /** 是否已经具备启动轮询服务的最低配置。 */
    public boolean isConfigured() {
        return appId.trim().length() > 0
                && appSecret.trim().length() > 0
                && chatId.trim().length() > 0;
    }

    /** 记住一个已处理的消息 ID（用于同一秒内多条消息的去重）。 */
    public void rememberMessageId(String messageId) {
        if (messageId == null || messageId.length() == 0) {
            return;
        }
        List<String> ids = new ArrayList<String>();
        ids.add(messageId);
        for (String existing : recentIds()) {
            if (ids.size() >= MAX_RECENT_IDS) {
                break;
            }
            if (!existing.equals(messageId)) {
                ids.add(existing);
            }
        }
        setRecentIds(ids);
    }

    public List<String> recentIds() {
        List<String> ids = new ArrayList<String>();
        if (recentMessageIds == null) {
            return ids;
        }
        for (String part : recentMessageIds.split(",")) {
            String id = part.trim();
            if (id.length() > 0) {
                ids.add(id);
            }
        }
        return ids;
    }

    public void setRecentIds(Collection<String> ids) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String id : ids) {
            if (id == null || id.length() == 0) {
                continue;
            }
            if (count >= MAX_RECENT_IDS) {
                break;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
            count++;
        }
        recentMessageIds = sb.toString();
    }
}
