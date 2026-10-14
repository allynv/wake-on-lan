package com.wol.app;

/**
 * 一台待唤醒设备的配置。
 *
 * <p>纯数据模型：不含 Android 依赖、也不含 JSON 依赖（序列化由 {@link DeviceStore} 负责），
 * 因此可以直接在 JVM 上单测。
 */
public class Device {

    public long id;
    public String name = "";
    public String mac = "";
    /** 可选：IPv4 地址。填了就单播给它，没填则发广播。 */
    public String ip = "";
    /** 唤醒端口，默认 9。 */
    public int port = 9;
    /** 可选：手工指定广播地址（旧版本数据可能带），留空则用本机网段广播。 */
    public String broadcast = "";
    public String note = "";

    public boolean hasIp() {
        return ip != null && ip.trim().length() > 0;
    }
}
