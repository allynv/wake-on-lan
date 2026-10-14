import com.wol.app.Device;
import com.wol.app.FeishuErrors;
import com.wol.app.FeishuTexts;
import com.wol.app.Texts;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 文案层与工具类的单元测试（纯 Java，不依赖 Android）。
 *
 * <p>覆盖：设备清单排版、唤醒成功/失败文案、歧义与找不到设备、时间与数字工具。
 * 由 tools\test-packet.ps1 编译并运行。
 */
public final class FeishuTextsTest {

    private static int checks = 0;
    private static int failures = 0;

    public static void main(String[] args) {
        testClock();
        testParseIntOr();
        testClamp();
        testDeviceList();
        testUsageAndNotFound();
        testAmbiguous();
        testWakeOk();
        testWakeFailed();
        testHelpAndApiError();
        testErrorClassification();

        System.out.println("-----------------------------------------");
        System.out.println("飞书文案检查项: " + checks + "，失败: " + failures);
        if (failures > 0) {
            System.out.println("结果: 失败");
            System.exit(1);
        }
        System.out.println("结果: 全部通过");
    }

    // ------------------------------------------------------------------ Texts

    private static void testClock() {
        assertEquals("0 显示占位符", "—", Texts.clock(0L));
        assertEquals("负数显示占位符", "—", Texts.clock(-1L));
        String now = Texts.clock(System.currentTimeMillis());
        assertTrue("正常时间形如 HH:mm:ss（实际 " + now + "）",
                now.length() == 8 && now.charAt(2) == ':' && now.charAt(5) == ':');
    }

    private static void testParseIntOr() {
        assertEquals("正常解析", 15, Texts.parseIntOr("15", 10, 5, 120));
        assertEquals("带空格", 7, Texts.parseIntOr("  7  ", 10, 5, 120));
        assertEquals("空串用默认值", 10, Texts.parseIntOr("", 10, 5, 120));
        assertEquals("null 用默认值", 10, Texts.parseIntOr(null, 10, 5, 120));
        assertEquals("非数字用默认值", 10, Texts.parseIntOr("abc", 10, 5, 120));
        assertEquals("越界用默认值", 10, Texts.parseIntOr("999", 10, 5, 120));
        assertEquals("低于下限用默认值", 10, Texts.parseIntOr("1", 10, 5, 120));
        assertEquals("等于边界值可用", 5, Texts.parseIntOr("5", 10, 5, 120));
    }

    private static void testClamp() {
        assertEquals("低于下限收敛到下限", 5, Texts.clamp(3, 5, 10));
        assertEquals("高于上限收敛到上限", 10, Texts.clamp(20, 5, 10));
        assertEquals("区间内原样返回", 7, Texts.clamp(7, 5, 10));
    }

    // ------------------------------------------------------------------ 文案

    private static void testDeviceList() {
        List<Device> devices = new ArrayList<Device>();
        devices.add(device("书房台式机", "E8:9C:25:DE:82:57", "192.168.0.3", 9, "主力机"));
        devices.add(device("NAS 存储", "AA:BB:CC:DD:EE:FF", "", 7, ""));

        String text = FeishuTexts.deviceList(devices);
        assertTrue("有标题", text.contains("📋 设备列表："));
        assertTrue("序号从 1 开始", text.contains("1. 书房台式机"));
        assertTrue("第二台序号为 2", text.contains("2. NAS 存储"));
        assertTrue("含 MAC", text.contains("E8:9C:25:DE:82:57"));
        assertTrue("填了 IP 显示 IP:端口", text.contains("192.168.0.3:9"));
        assertTrue("没填 IP 显示广播:端口", text.contains("广播 :7"));
        assertTrue("带备注", text.contains("(主力机)"));
        assertTrue("无备注时不出现空括号", !text.contains("()"));

        assertEquals("空列表给出提示", "还没有配置任何设备。",
                FeishuTexts.deviceList(new ArrayList<Device>()));
        assertEquals("null 列表给出提示", "还没有配置任何设备。", FeishuTexts.deviceList(null));
    }

    private static void testUsageAndNotFound() {
        List<Device> devices = new ArrayList<Device>();
        devices.add(device("书房台式机", "E8:9C:25:DE:82:57", "192.168.0.3", 9, ""));

        String usage = FeishuTexts.usage(devices);
        assertTrue("用法提示", usage.startsWith("用法：/wake 设备名"));
        assertTrue("用法里带设备清单", usage.contains("1. 书房台式机"));

        String notFound = FeishuTexts.notFound("卧室", devices);
        assertTrue("提示没找到", notFound.contains("没找到「卧室」"));
        assertTrue("顺带列出可用设备", notFound.contains("书房台式机"));
    }

    private static void testAmbiguous() {
        List<String> names = Arrays.asList("书房台式机", "客厅主机");
        List<Integer> hits = Arrays.asList(Integer.valueOf(0), Integer.valueOf(1));
        String text = FeishuTexts.ambiguous("机", names, hits);
        assertTrue("点出歧义", text.contains("匹配到多台设备"));
        assertTrue("列出候选 1", text.contains("书房台式机"));
        assertTrue("列出候选 2", text.contains("客厅主机"));
        assertTrue("给出解决办法", text.contains("序号"));
    }

    private static void testWakeOk() {
        FeishuTexts.Wake wake = new FeishuTexts.Wake();
        wake.packets = 1;
        wake.name = "书房台式机";
        wake.mac = "E8:9C:25:DE:82:57";
        wake.target = "192.168.0.255";
        wake.port = "9";
        wake.localCidr = "192.168.0.6/24";

        String plain = FeishuTexts.wakeOk(wake);
        assertTrue("成功标记", plain.contains("✅ 已发送 1 个魔术包 → 书房台式机"));
        assertTrue("含 MAC", plain.contains("（E8:9C:25:DE:82:57）"));
        assertTrue("含目标与端口", plain.contains("目标 192.168.0.255 :9"));
        assertTrue("含来源网段", plain.contains("来源 192.168.0.6/24"));
        assertTrue("没有回退时不出现 ARP 说明", !plain.contains("解析不到 ARP"));

        wake.localCidr = "";
        assertTrue("无来源网段时不出现空行", !FeishuTexts.wakeOk(wake).contains("来源"));

        wake.fallbackFrom = "192.168.0.3";
        wake.fallbackTo = "192.168.0.255";
        String fallback = FeishuTexts.wakeOk(wake);
        assertTrue("有回退时说明原因",
                fallback.contains("（192.168.0.3 解析不到 ARP，已改用广播 192.168.0.255）"));
    }

    private static void testWakeFailed() {
        String text = FeishuTexts.wakeFailed("书房台式机", "未连接 Wi-Fi，无法发送唤醒包");
        assertTrue("失败标记", text.contains("⚠️ 唤醒失败"));
        assertTrue("含失败原因", text.contains("未连接 Wi-Fi"));
        assertTrue("含目标设备", text.contains("（目标：书房台式机）"));
    }

    private static void testHelpAndApiError() {
        String help = FeishuTexts.help();
        assertTrue("帮助含 /wake", help.contains("/wake 设备名"));
        assertTrue("帮助含 /list", help.contains("/list"));
        assertTrue("帮助含 /help", help.contains("/help"));
        assertTrue("帮助说明可用序号", help.contains("/wake 1"));

        String error = FeishuTexts.apiError("code=99991672 缺少权限");
        assertTrue("错误文案含原因", error.contains("code=99991672"));
        assertTrue("错误文案含排查方向", error.contains("权限"));
    }

    // ------------------------------------------------------------------ 错误分类

    private static void testErrorClassification() {
        // 需要用户动手的：凭证 / 权限 / 应用状态
        assertTrue("凭证无效属需要处理", FeishuErrors.isActionable(99991663));
        assertTrue("Secret 变了属需要处理", FeishuErrors.isActionable(99991664));
        assertTrue("缺少权限属需要处理", FeishuErrors.isActionable(99991672));
        assertTrue("缺少接口权限属需要处理", FeishuErrors.isActionable(230027));
        assertTrue("应用未启用属需要处理", FeishuErrors.isActionable(232034));
        assertTrue("不在可用范围属需要处理", FeishuErrors.isActionable(230013));
        assertTrue("机器人不在会话属需要处理", FeishuErrors.isActionable(230002));

        // 临时故障：网络层失败（code <= 0）与飞书服务端内部错误
        assertTrue("网络超时属临时故障", FeishuErrors.isTransient(-1));
        assertTrue("飞书内部错误 2200 属临时故障", FeishuErrors.isTransient(2200));
        assertTrue("限流 99991400 属临时故障", FeishuErrors.isTransient(99991400));
        assertTrue("未知错误码按临时故障处理", FeishuErrors.isTransient(123456));

        assertTrue("两类判定互斥", FeishuErrors.isActionable(2200) != FeishuErrors.isTransient(2200));
    }

    // ------------------------------------------------------------------ 工具

    private static Device device(String name, String mac, String ip, int port, String note) {
        Device d = new Device();
        d.name = name;
        d.mac = mac;
        d.ip = ip;
        d.port = port;
        d.note = note;
        return d;
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
}
