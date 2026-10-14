import com.wol.app.FeishuCommand;

import java.util.Arrays;
import java.util.List;

/**
 * 飞书指令解析与设备匹配的单元测试（不依赖 Android），由 tools\test-packet.ps1 编译并运行。
 */
public final class FeishuCommandTest {

    private static int checks = 0;
    private static int failures = 0;

    private static final List<String> NAMES =
            Arrays.asList("书房台式机", "客厅主机", "NAS 存储");

    public static void main(String[] args) {
        testParseWithPrefix();
        testParseWithoutPrefix();
        testParseWithPrefixButUnknownVerb();
        testParseIgnoresChat();
        testMatchDevice();
        testAmbiguous();
        testAllowList();

        System.out.println("-----------------------------------------");
        System.out.println("飞书指令检查项: " + checks + "，失败: " + failures);
        if (failures > 0) {
            System.out.println("结果: 失败");
            System.exit(1);
        }
        System.out.println("结果: 全部通过");
    }

    // ------------------------------------------------------------------ 解析

    private static void testParseWithPrefix() {
        FeishuCommand c = FeishuCommand.parse("/wake 书房", "/");
        assertTrue("/wake 书房 -> WAKE", c.type == FeishuCommand.TYPE_WAKE);
        assertEquals("/wake 书房 的参数", "书房", c.arg);

        c = FeishuCommand.parse("/wake 书房 台式机", "/");
        assertTrue("多词设备名仍属于同一个参数", c.type == FeishuCommand.TYPE_WAKE);
        assertEquals("多词参数原样保留", "书房 台式机", c.arg);

        c = FeishuCommand.parse("   /唤醒   客厅   ", "/");
        assertTrue("中文动词 + 多余空格", c.type == FeishuCommand.TYPE_WAKE);
        assertEquals("首尾空格被去掉", "客厅", c.arg);

        c = FeishuCommand.parse("/WAKE 客厅", "/");
        assertTrue("动词大小写不敏感", c.type == FeishuCommand.TYPE_WAKE);
        assertEquals("大小写不敏感参数", "客厅", c.arg);

        assertTrue("/list -> LIST", FeishuCommand.parse("/list", "/").type == FeishuCommand.TYPE_LIST);
        assertTrue("/ls -> LIST", FeishuCommand.parse("/ls", "/").type == FeishuCommand.TYPE_LIST);
        assertTrue("/设备 -> LIST", FeishuCommand.parse("/设备", "/").type == FeishuCommand.TYPE_LIST);
        assertTrue("/help -> HELP", FeishuCommand.parse("/help", "/").type == FeishuCommand.TYPE_HELP);
        assertTrue("/? -> HELP", FeishuCommand.parse("/?", "/").type == FeishuCommand.TYPE_HELP);
        assertTrue("/帮助 -> HELP", FeishuCommand.parse("/帮助", "/").type == FeishuCommand.TYPE_HELP);
        assertTrue("单独一个斜杠 -> HELP", FeishuCommand.parse("/", "/").type == FeishuCommand.TYPE_HELP);
    }

    private static void testParseWithoutPrefix() {
        assertTrue("空前缀：唤醒 书房 -> WAKE",
                FeishuCommand.parse("唤醒 书房", "").type == FeishuCommand.TYPE_WAKE);
        assertEquals("空前缀参数", "书房", FeishuCommand.parse("唤醒 书房", "").arg);
        assertTrue("空前缀：列表 -> LIST",
                FeishuCommand.parse("列表", "").type == FeishuCommand.TYPE_LIST);
        assertTrue("空前缀：带斜杠也认",
                FeishuCommand.parse("/wake 书房", "").type == FeishuCommand.TYPE_WAKE);
    }

    private static void testParseWithPrefixButUnknownVerb() {
        FeishuCommand c = FeishuCommand.parse("/foobar 书房", "/");
        assertTrue("前缀命中但动词不认识 -> 回 HELP（而不是沉默）", c.type == FeishuCommand.TYPE_HELP);
    }

    private static void testParseIgnoresChat() {
        assertTrue("不带前缀的闲聊 -> NONE", FeishuCommand.parse("随便聊聊", "/").type == FeishuCommand.TYPE_NONE);
        assertTrue("不带前缀的动词 -> NONE（前缀为 / 时）",
                FeishuCommand.parse("wake 书房", "/").type == FeishuCommand.TYPE_NONE);
        assertTrue("空前缀下的闲聊 -> NONE", FeishuCommand.parse("嗯嗯好的", "").type == FeishuCommand.TYPE_NONE);
        assertTrue("null -> NONE", FeishuCommand.parse(null, "/").type == FeishuCommand.TYPE_NONE);
    }

    // ------------------------------------------------------------------ 设备匹配

    private static void testMatchDevice() {
        assertEquals("名称完全相等", 0, FeishuCommand.matchDevice("书房台式机", NAMES));
        assertEquals("名称唯一包含", 0, FeishuCommand.matchDevice("书房", NAMES));
        assertEquals("另一个唯一包含", 1, FeishuCommand.matchDevice("客厅", NAMES));
        assertEquals("忽略大小写", 2, FeishuCommand.matchDevice("nas 存储", NAMES));
        assertEquals("序号 1", 0, FeishuCommand.matchDevice("1", NAMES));
        assertEquals("序号 3", 2, FeishuCommand.matchDevice("3", NAMES));
        assertEquals("序号越界 -> 没找到", FeishuCommand.MATCH_NONE, FeishuCommand.matchDevice("9", NAMES));
        assertEquals("找不到", FeishuCommand.MATCH_NONE, FeishuCommand.matchDevice("卧室", NAMES));
        assertEquals("空参数", FeishuCommand.MATCH_NONE, FeishuCommand.matchDevice("", NAMES));
    }

    private static void testAmbiguous() {
        assertEquals("“机”命中两台 -> 歧义", FeishuCommand.MATCH_AMBIGUOUS, FeishuCommand.matchDevice("机", NAMES));
        List<Integer> hits = FeishuCommand.listMatches("机", NAMES);
        assertEquals("歧义时能列出候选数量", 2, hits.size());
    }

    // ------------------------------------------------------------------ 白名单

    private static void testAllowList() {
        assertTrue("名单为空 -> 放行", FeishuCommand.isAllowed("ou_x", ""));
        assertTrue("名单为空串 -> 放行", FeishuCommand.isAllowed("ou_x", "   "));
        assertTrue("命中名单", FeishuCommand.isAllowed("ou_b", "ou_a, ou_b"));
        assertTrue("命中名单（忽略大小写与空格）", FeishuCommand.isAllowed("OU_B", " ou_a ,ou_b "));
        assertTrue("未命中 -> 拒绝", !FeishuCommand.isAllowed("ou_c", "ou_a,ou_b"));
        assertTrue("发送者为空且名单非空 -> 拒绝", !FeishuCommand.isAllowed(null, "ou_a"));
    }

    // ------------------------------------------------------------------ 工具

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
