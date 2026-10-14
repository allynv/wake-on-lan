package com.wol.app;

/**
 * MAC 地址解析与格式化。
 * 纯 Java、无 Android 依赖，可直接在 JVM 上做单元测试。
 */
public final class MacAddress {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private MacAddress() {
    }

    /**
     * 解析常见写法：AA:BB:CC:DD:EE:FF、aa-bb-cc-dd-ee-ff、AABBCCDDEEFF、带空格等。
     *
     * @return 6 字节 MAC；格式非法返回 null。
     */
    public static byte[] parse(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == ':' || c == '-' || c == '.' || c == ' ' || c == '\t' || c == '_') {
                continue;
            }
            sb.append(c);
        }
        if (sb.length() != 12) {
            return null;
        }
        byte[] out = new byte[6];
        for (int i = 0; i < 6; i++) {
            int hi = hex(sb.charAt(i * 2));
            int lo = hex(sb.charAt(i * 2 + 1));
            if (hi < 0 || lo < 0) {
                return null;
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    /** 6 字节 MAC 转 "AA:BB:CC:DD:EE:FF"。 */
    public static String format(byte[] mac) {
        if (mac == null || mac.length != 6) {
            return "";
        }
        StringBuilder sb = new StringBuilder(17);
        for (int i = 0; i < 6; i++) {
            if (i > 0) {
                sb.append(':');
            }
            sb.append(HEX[(mac[i] >> 4) & 0x0F]).append(HEX[mac[i] & 0x0F]);
        }
        return sb.toString();
    }

    /** 归一化为标准大写写法；无法解析时返回去空格的大写原文，方便回显。 */
    public static String normalize(String raw) {
        byte[] mac = parse(raw);
        if (mac != null) {
            return format(mac);
        }
        return raw == null ? "" : raw.trim().toUpperCase();
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }
}
