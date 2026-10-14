import com.wol.app.MacAddress;
import com.wol.app.NetMath;
import com.wol.app.WolPacket;

/**
 * 纯逻辑层单元测试（不依赖 Android），由 tools\test-packet.ps1 编译并运行。
 * 覆盖：MAC 解析、102 字节魔术包结构、子网广播计算。
 */
public final class WolPacketTest {

    private static int checks = 0;
    private static int failures = 0;

    public static void main(String[] args) {
        testMacParse();
        testMacFormat();
        testPacketStructure();
        testPacketInvalidInput();
        testIpParsing();
        testBroadcast();

        System.out.println("-----------------------------------------");
        System.out.println("检查项: " + checks + "，失败: " + failures);
        if (failures > 0) {
            System.out.println("结果: 失败");
            System.exit(1);
        }
        System.out.println("结果: 全部通过");
    }

    // ------------------------------------------------------------------ MAC

    private static void testMacParse() {
        byte[] expected = new byte[] { (byte) 0xAA, (byte) 0xBB, (byte) 0xCC, (byte) 0xDD, (byte) 0xEE, (byte) 0xFF };

        assertBytes("解析冒号写法", expected, MacAddress.parse("AA:BB:CC:DD:EE:FF"));
        assertBytes("解析小写横线", expected, MacAddress.parse("aa-bb-cc-dd-ee-ff"));
        assertBytes("解析无分隔符", expected, MacAddress.parse("aabbccddeeff"));
        assertBytes("解析带空格", expected, MacAddress.parse("  AA BB CC DD EE FF  "));

        assertTrue("空串应非法", MacAddress.parse("") == null);
        assertTrue("null 应非法", MacAddress.parse(null) == null);
        assertTrue("少一段应非法", MacAddress.parse("AA:BB:CC:DD:EE") == null);
        assertTrue("多一段应非法", MacAddress.parse("AA:BB:CC:DD:EE:FF:00") == null);
        assertTrue("非十六进制应非法", MacAddress.parse("GG:BB:CC:DD:EE:FF") == null);
        assertTrue("位数不足应非法", MacAddress.parse("A:BB:CC:DD:EE:FF") == null);
    }

    private static void testMacFormat() {
        assertEquals("格式化为大写冒号", "AA:BB:CC:DD:EE:FF",
                MacAddress.format(MacAddress.parse("aabbccddeeff")));
        assertEquals("规范化混合写法", "00:1A:2B:3C:4D:5E",
                MacAddress.normalize("00-1a-2b-3c-4d-5e"));
    }

    // ------------------------------------------------------------------ 魔术包

    private static void testPacketStructure() {
        byte[] mac = MacAddress.parse("00:11:22:33:44:55");
        byte[] packet = WolPacket.build(mac);

        assertEquals("魔术包总长度", 102, packet.length);
        assertTrue("前 6 字节必须是 0xFF", packet[0] == (byte) 0xFF && packet[1] == (byte) 0xFF
                && packet[2] == (byte) 0xFF && packet[3] == (byte) 0xFF
                && packet[4] == (byte) 0xFF && packet[5] == (byte) 0xFF);

        boolean repeated = true;
        for (int r = 0; r < 16; r++) {
            for (int i = 0; i < 6; i++) {
                if (packet[6 + r * 6 + i] != mac[i]) {
                    repeated = false;
                }
            }
        }
        assertTrue("MAC 必须连续重复 16 次", repeated);

        String hex = toHex(packet);
        assertTrue("包内容与标准魔术包一致",
                hex.equals("FFFFFFFFFFFF001122334455001122334455001122334455001122334455"
                        + "001122334455001122334455001122334455001122334455"
                        + "001122334455001122334455001122334455001122334455"
                        + "001122334455001122334455001122334455001122334455"));
    }

    private static void testPacketInvalidInput() {
        try {
            WolPacket.build("not-a-mac");
            assertTrue("非法 MAC 应抛异常", false);
        } catch (IllegalArgumentException expected) {
            assertTrue("非法 MAC 抛 IllegalArgumentException", true);
        }
        try {
            WolPacket.build((byte[]) null);
            assertTrue("null MAC 应抛异常", false);
        } catch (IllegalArgumentException expected) {
            assertTrue("null MAC 抛 IllegalArgumentException", true);
        }
    }

    // ------------------------------------------------------------------ 网络

    private static void testIpParsing() {
        assertEquals("解析 IPv4", "192.168.1.10", NetMath.toString(NetMath.toBytes("192.168.1.10")));
        assertTrue("256 越界非法", NetMath.toBytes("192.168.1.256") == null);
        assertTrue("段数不足非法", NetMath.toBytes("192.168.1") == null);
        assertTrue("段数过多非法", NetMath.toBytes("192.168.1.1.1") == null);
        assertTrue("字母非法", NetMath.toBytes("a.b.c.d") == null);
        assertTrue("空串非法", NetMath.toBytes("") == null);
        assertTrue("前导零合法", NetMath.toBytes("192.168.001.010") != null);
        assertTrue("末尾点非法", NetMath.toBytes("192.168.1.10.") == null);
    }

    private static void testBroadcast() {
        assertEquals("192.168.1.10/24 -> .255", "192.168.1.255",
                bcast("192.168.1.10", 24));
        assertEquals("10.1.2.3/16 -> 10.1.255.255", "10.1.255.255",
                bcast("10.1.2.3", 16));
        assertEquals("192.168.1.130/25 -> .255", "192.168.1.255",
                bcast("192.168.1.130", 25));
        assertEquals("192.168.1.10/30 -> .11", "192.168.1.11",
                bcast("192.168.1.10", 30));
        assertEquals("1.2.3.4/0 -> 全 255", "255.255.255.255",
                bcast("1.2.3.4", 0));
        assertEquals("1.2.3.4/32 -> 自身", "1.2.3.4",
                bcast("1.2.3.4", 32));
        assertTrue("非法前缀返回 null", NetMath.broadcastAddress(NetMath.toBytes("1.2.3.4"), 33) == null);
    }

    // ------------------------------------------------------------------ 工具

    private static String bcast(String ip, int prefix) {
        byte[] out = NetMath.broadcastAddress(NetMath.toBytes(ip), prefix);
        return out == null ? "null" : NetMath.toString(out);
    }

    private static String toHex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02X", Integer.valueOf(b & 0xFF)));
        }
        return sb.toString();
    }

    private static void assertTrue(String name, boolean condition) {
        checks++;
        if (!condition) {
            failures++;
            System.out.println("[FAIL] " + name);
        }
    }

    private static void assertEquals(String name, int expected, int actual) {
        assertTrue(name + "（期望 " + expected + "，实际 " + actual + "）", expected == actual);
    }

    private static void assertEquals(String name, String expected, String actual) {
        assertTrue(name + "（期望 " + expected + "，实际 " + actual + "）",
                expected == null ? actual == null : expected.equals(actual));
    }

    private static void assertBytes(String name, byte[] expected, byte[] actual) {
        boolean same = expected != null && actual != null && expected.length == actual.length;
        if (same) {
            for (int i = 0; i < expected.length; i++) {
                if (expected[i] != actual[i]) {
                    same = false;
                }
            }
        }
        assertTrue(name, same);
    }
}
