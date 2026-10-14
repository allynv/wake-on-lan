package com.wol.app;

/**
 * 飞书错误分类：决定一个错误"要不要打扰用户"。
 *
 * <ul>
 *   <li><b>需要处理</b>（{@link #isActionable}）：凭证错误、权限未开通、应用未启用、机器人不在会话里 ——
 *       只有用户动手才能修好，值得推到飞书里提醒；</li>
 *   <li><b>临时故障</b>（{@link #isTransient}）：网络超时、连接失败、飞书服务端内部错误（例如 2200 Internal Error）——
 *       重试即可恢复，**不该往会话里发消息**。否则手机待机一整夜，你会收到一串
 *       "请检查 App ID / App Secret / 权限" 的误导性提示。</li>
 * </ul>
 *
 * <p>纯 Java，可在 JVM 上单测（见 tools/jvmtest/FeishuTextsTest.java）。
 */
public final class FeishuErrors {

    private FeishuErrors() {
    }

    /** 需要用户动手处理的错误码。 */
    public static boolean isActionable(int code) {
        switch (code) {
            // 凭证 / 应用身份
            case 99991661:  // 请求缺少有效凭证
            case 99991663:  // tenant_access_token 无效或过期
            case 99991664:  // 凭证与 app_id 不匹配（通常是 App Secret 变了）
            case 99991668:  // 凭证与租户不匹配
            case 99991672:  // 应用尚未开通所需权限（改了权限没重新发布版本）
                // 机器人 / 会话 / 权限
            case 230002:    // 机器人不在该会话中
            case 230006:    // 未开启机器人能力
            case 230013:    // 用户不在应用可用范围内
            case 230027:    // 缺少接口权限（多为未重新发布版本）
            case 230034:    // 接收者 ID 无效
            case 230053:    // 用户已停用该机器人的消息
            case 232025:    // 未启用机器人能力
            case 232034:    // 应用在本租户未安装或未启用
                return true;
            default:
                return false;
        }
    }

    /** 临时故障：重试即可，不要打扰用户。 */
    public static boolean isTransient(int code) {
        return !isActionable(code);
    }
}
