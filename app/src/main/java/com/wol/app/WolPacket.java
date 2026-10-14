package com.wol.app;

/**
 * Wake-on-LAN 魔术包构造：6 字节 0xFF + 16 次重复的 6 字节 MAC = 102 字节。
 * 纯 Java、无 Android 依赖，可直接在 JVM 上做单元测试。
 */
public final class WolPacket {

    /** 魔术包固定长度。 */
    public static final int LENGTH = 102;
    /** MAC 重复次数。 */
    public static final int REPEAT = 16;

    private WolPacket() {
    }

    public static byte[] build(byte[] mac) {
        if (mac == null || mac.length != 6) {
            throw new IllegalArgumentException("MAC 必须是 6 字节");
        }
        byte[] packet = new byte[LENGTH];
        for (int i = 0; i < 6; i++) {
            packet[i] = (byte) 0xFF;
        }
        for (int r = 0; r < REPEAT; r++) {
            System.arraycopy(mac, 0, packet, 6 + r * 6, 6);
        }
        return packet;
    }

    public static byte[] build(String macText) {
        byte[] mac = MacAddress.parse(macText);
        if (mac == null) {
            throw new IllegalArgumentException("MAC 格式非法：" + macText);
        }
        return build(mac);
    }
}
