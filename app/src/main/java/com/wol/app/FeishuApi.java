package com.wol.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * 飞书开放平台最小客户端：只用 {@link HttpURLConnection} + {@code org.json}（Android 框架自带），
 * 不引入任何第三方库。只实现本应用需要的 5 个接口：
 *
 * <ol>
 *   <li>自建应用获取 tenant_access_token</li>
 *   <li>通过手机号/邮箱获取用户 open_id</li>
 *   <li>以应用身份给用户发消息（顺带从响应里取回单聊 chat_id）</li>
 *   <li>以应用身份给会话发消息（回复）</li>
 *   <li>获取会话历史消息（轮询指令）</li>
 * </ol>
 */
public final class FeishuApi {

    /** 飞书（国内版）服务地址；Lark 国际版为 https://open.larksuite.com 。 */
    public static final String DEFAULT_BASE = "https://open.feishu.cn";

    /**
     * 超时时间放宽到 15/20 秒。
     *
     * <p>原因是手机夜间息屏时 Wi-Fi 会进省电模式，第一次 TLS 握手偶发超过 10 秒，
     * 用原来 10 秒的上限会把这些"其实能成功"的请求判成失败。
     */
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 20000;

    private FeishuApi() {
    }

    /** 接口调用失败：{@link #code} 为飞书错误码（HTTP/解析层失败时为 -1）。 */
    public static class ApiException extends Exception {
        public final int code;
        public final String logId;

        public ApiException(int code, String message, String logId) {
            super(message);
            this.code = code;
            this.logId = logId == null ? "" : logId;
        }

        public ApiException(String message) {
            this(-1, message, "");
        }

        public String describe() {
            if (code > 0) {
                return "code=" + code + " " + getMessage() + (logId.length() > 0 ? "（log_id=" + logId + "）" : "");
            }
            return getMessage();
        }
    }

    /** 租户凭证。 */
    public static final class Token {
        public String value = "";
        /** 提前 5 分钟视为过期，避免边界失败。 */
        public long expireAtMs;
    }

    /** 一条会话消息（只提取需要的字段）。 */
    public static final class Message {
        public String messageId = "";
        public long createTimeMs;
        public String msgType = "";
        public boolean deleted;
        public String senderId = "";
        public String senderType = "";
        public String text = "";
    }

    /** 取 tenant_access_token。 */
    public static Token fetchToken(String baseUrl, String appId, String appSecret) throws ApiException {
        JSONObject body = new JSONObject();
        try {
            body.put("app_id", appId);
            body.put("app_secret", appSecret);
        } catch (Exception e) {
            throw new ApiException("构造请求失败：" + e.getMessage());
        }
        JSONObject resp = request(baseUrl, "POST", "/open-apis/auth/v3/tenant_access_token/internal", null, body);
        Token token = new Token();
        token.value = resp.optString("tenant_access_token", "");
        long expire = resp.optLong("expire", 7200L);
        token.expireAtMs = System.currentTimeMillis() + Math.max(60L, expire - 300L) * 1000L;
        if (token.value.length() == 0) {
            throw new ApiException(-1, "返回里没有 tenant_access_token", "");
        }
        return token;
    }

    /** 通过手机号或邮箱换 open_id（需要权限 contact:user.id:readonly）。 */
    public static String resolveOpenId(String baseUrl, String token, String mobile, String email) throws ApiException {
        JSONObject body = new JSONObject();
        try {
            if (mobile != null && mobile.trim().length() > 0) {
                body.put("mobiles", new JSONArray().put(mobile.trim()));
            }
            if (email != null && email.trim().length() > 0) {
                body.put("emails", new JSONArray().put(email.trim()));
            }
        } catch (Exception e) {
            throw new ApiException("构造请求失败：" + e.getMessage());
        }
        if (body.length() == 0) {
            throw new ApiException(-1, "没有填写手机号或邮箱", "");
        }
        JSONObject resp = request(baseUrl, "POST",
                "/open-apis/contact/v3/users/batch_get_id?user_id_type=open_id", token, body);
        JSONObject data = resp.optJSONObject("data");
        JSONArray list = data == null ? null : data.optJSONArray("user_list");
        if (list != null && list.length() > 0) {
            JSONObject first = list.optJSONObject(0);
            String id = first == null ? "" : first.optString("user_id", "");
            if (id.length() > 0) {
                return id;
            }
        }
        throw new ApiException(-1, "没有查到用户 ID：请核对手机号/邮箱，并确认已开通 contact:user.id:readonly 且重新发布了应用版本", "");
    }

    /**
     * 给某个用户发消息，返回该单聊的 chat_id —— 这是机器人单聊 chat_id 的唯一自动来源
     * （飞书的「获取机器人所在群列表」不返回 p2p 单聊）。
     */
    public static String sendTextToOpenId(String baseUrl, String token, String openId, String text) throws ApiException {
        return sendText(baseUrl, token, "open_id", openId, text);
    }

    /** 给某个会话（单聊/群聊）发消息。 */
    public static void sendTextToChat(String baseUrl, String token, String chatId, String text) throws ApiException {
        sendText(baseUrl, token, "chat_id", chatId, text);
    }

    private static String sendText(String baseUrl, String token, String idType, String receiveId, String text)
            throws ApiException {
        JSONObject content = new JSONObject();
        JSONObject body = new JSONObject();
        try {
            content.put("text", text);
            body.put("receive_id", receiveId);
            body.put("msg_type", "text");
            body.put("content", content.toString());
        } catch (Exception e) {
            throw new ApiException("构造请求失败：" + e.getMessage());
        }
        JSONObject resp = request(baseUrl, "POST",
                "/open-apis/im/v1/messages?receive_id_type=" + idType, token, body);
        JSONObject data = resp.optJSONObject("data");
        return data == null ? "" : data.optString("chat_id", "");
    }

    /**
     * 拉取会话历史消息（轮询用）。
     *
     * @param startTimeSec 起始时间（秒），0 表示不限
     */
    public static List<Message> listMessages(String baseUrl, String token, String chatId,
                                             long startTimeSec, int pageSize) throws ApiException {
        StringBuilder path = new StringBuilder("/open-apis/im/v1/messages?container_id_type=chat");
        path.append("&container_id=").append(urlEncode(chatId));
        path.append("&sort_type=ByCreateTimeAsc");
        path.append("&page_size=").append(pageSize < 1 ? 20 : (pageSize > 50 ? 50 : pageSize));
        if (startTimeSec > 0) {
            path.append("&start_time=").append(startTimeSec);
        }
        JSONObject resp = request(baseUrl, "GET", path.toString(), token, null);

        List<Message> out = new ArrayList<Message>();
        JSONObject data = resp.optJSONObject("data");
        JSONArray items = data == null ? null : data.optJSONArray("items");
        if (items == null) {
            return out;
        }
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) {
                continue;
            }
            Message message = new Message();
            message.messageId = item.optString("message_id", "");
            message.createTimeMs = parseLong(item.optString("create_time", "0"));
            message.msgType = item.optString("msg_type", "");
            message.deleted = item.optBoolean("deleted", false);
            JSONObject sender = item.optJSONObject("sender");
            if (sender != null) {
                message.senderId = sender.optString("id", "");
                message.senderType = sender.optString("sender_type", "");
            }
            message.text = extractText(item);
            out.add(message);
        }
        return out;
    }

    // ------------------------------------------------------------------ 内部

    private static String extractText(JSONObject item) {
        JSONObject body = item.optJSONObject("body");
        if (body == null) {
            return "";
        }
        String content = body.optString("content", "");
        if (content.length() == 0) {
            return "";
        }
        try {
            return new JSONObject(content).optString("text", "");
        } catch (Exception e) {
            // 非文本消息（或结构异常）时原样返回，交由上层按 msg_type 过滤
            return content;
        }
    }

    private static JSONObject request(String baseUrl, String method, String path, String token, JSONObject body)
            throws ApiException {
        HttpURLConnection connection = null;
        String logId = "";
        try {
            URL url = new URL(normalizeBase(baseUrl) + path);
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept", "application/json");
            if (token != null && token.length() > 0) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            if (body != null) {
                byte[] payload = body.toString().getBytes("UTF-8");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(payload.length);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                OutputStream out = connection.getOutputStream();
                try {
                    out.write(payload);
                    out.flush();
                } finally {
                    out.close();
                }
            }
            logId = header(connection, "X-Tt-Logid");
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();
            String text = readAll(stream);
            if (text == null || text.length() == 0) {
                throw new ApiException(-1, "HTTP " + status + " 但响应为空", logId);
            }
            JSONObject resp = new JSONObject(text);
            int code = resp.optInt("code", 0);
            if (code != 0) {
                throw new ApiException(code, resp.optString("msg", "未知错误"), logId);
            }
            return resp;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.toString() : e.getMessage();
            throw new ApiException(-1, "请求失败：" + message, logId);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String header(HttpURLConnection connection, String name) {
        try {
            String value = connection.getHeaderField(name);
            return value == null ? "" : value;
        } catch (Exception e) {
            return "";
        }
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(4096);
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    private static String normalizeBase(String baseUrl) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        if (base.length() == 0) {
            base = DEFAULT_BASE;
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    private static String urlEncode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (Exception e) {
            return value == null ? "" : value;
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
