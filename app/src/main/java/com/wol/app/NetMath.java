package com.wol.app;

/**
 * IPv4 地址与子网运算（CIDR 前缀通用：/16 /23 /24 /25 等）。
 * 纯 Java、无 Android 依赖，可直接在 JVM 上做单元测试。
 */
public final class NetMath {

    private NetMath() {
    }

    /** "192.168.1.10" -> 4 字节；非法返回 null。 */
    public static byte[] toBytes(String ipv4) {
        if (ipv4 == null) {
            return null;
        }
        String text = ipv4.trim();
        if (text.length() == 0 || text.length() > 15) {
            return null;
        }
        byte[] out = new byte[4];
        int part = 0;
        int value = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                if (value < 0) {
                    value = 0;
                }
                value = value * 10 + (c - '0');
                if (value > 255) {
                    return null;
                }
            } else if (c == '.') {
                if (value < 0 || part >= 3) {
                    return null;
                }
                out[part++] = (byte) value;
                value = -1;
            } else {
                return null;
            }
        }
        if (value < 0 || part != 3) {
            return null;
        }
        out[3] = (byte) value;
        return out;
    }

    /** 4 字节 -> "192.168.1.10"。 */
    public static String toString(byte[] ip) {
        if (ip == null || ip.length != 4) {
            return "";
        }
        StringBuilder sb = new StringBuilder(15);
        for (int i = 0; i < 4; i++) {
            if (i > 0) {
                sb.append('.');
            }
            sb.append(ip[i] & 0xFF);
        }
        return sb.toString();
    }

    /**
     * 计算子网定向广播地址，例如 192.168.1.10/24 -> 192.168.1.255，10.1.2.3/16 -> 10.1.255.255。
     *
     * @param prefixLength 0-32
     * @return 广播地址；参数非法返回 null。
     */
    public static byte[] broadcastAddress(byte[] ip, int prefixLength) {
        if (ip == null || ip.length != 4 || prefixLength < 0 || prefixLength > 32) {
            return null;
        }
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            int bits = prefixLength - i * 8;
            int mask;
            if (bits >= 8) {
                mask = 0xFF;
            } else if (bits <= 0) {
                mask = 0x00;
            } else {
                mask = (0xFF << (8 - bits)) & 0xFF;
            }
            out[i] = (byte) ((ip[i] & mask) | (~mask & 0xFF));
        }
        return out;
    }
}
