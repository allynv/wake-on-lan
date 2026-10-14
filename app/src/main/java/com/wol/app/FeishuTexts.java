package com.wol.app;

import java.util.List;

/**
 * 飞书回复文案。
 *
 * <p>纯 Java：不依赖 Android，也不依赖 {@link WolSender}（唤醒结果通过 {@link Wake} 传入），
 * 因此可以在 JVM 上单测（见 tools/jvmtest/FeishuTextsTest.java）。
 */
public final class FeishuTexts {

    /** 一次唤醒的结果中，文案需要的字段。 */
    public static final class Wake {
        public int packets;
        public String name = "";
        public String mac = "";
        public String target = "";
        public String port = "";
        public String localCidr = "";
        public String fallbackFrom = "";
        public String fallbackTo = "";
    }

    private FeishuTexts() {
    }

    public static String help() {
        return "🤖 局域网唤醒指令\n"
                + "/wake 设备名 — 发送唤醒包（例：/wake 书房台式机）\n"
                + "/唤醒 设备名 — 同上\n"
                + "/list — 列出所有设备与序号\n"
                + "/help — 显示本帮助\n\n"
                + "也可用序号：/wake 1";
    }

    public static String noDevices() {
        return "还没有配置任何设备：请先在手机上打开 App，点右上角 + 添加设备。";
    }

    /** 设备清单：序号、名称、MAC、单播目标或"广播"、备注。 */
    public static String deviceList(List<Device> devices) {
        if (devices == null || devices.isEmpty()) {
            return "还没有配置任何设备。";
        }
        StringBuilder sb = new StringBuilder("📋 设备列表：\n");
        for (int i = 0; i < devices.size(); i++) {
            Device d = devices.get(i);
            sb.append(i + 1).append(". ").append(d.name)
                    .append("\n    ").append(d.mac);
            if (d.hasIp()) {
                sb.append("  ").append(d.ip).append(':').append(d.port);
            } else {
                sb.append("  广播 :").append(d.port);
            }
            if (d.note != null && d.note.length() > 0) {
                sb.append("  (").append(d.note).append(')');
            }
            sb.append('\n');
        }
        return sb.toString().trim();
    }

    public static String usage(List<Device> devices) {
        return "用法：/wake 设备名\n\n" + deviceList(devices);
    }

    /** 关键字匹配到多台设备时，列出候选让它写得更精确。 */
    public static String ambiguous(String arg, List<String> names, List<Integer> hits) {
        StringBuilder sb = new StringBuilder("❓「" + arg + "」匹配到多台设备：\n");
        for (int i = 0; i < hits.size(); i++) {
            sb.append(" · ").append(names.get(hits.get(i).intValue())).append('\n');
        }
        return sb.append("\n请写得更精确，或用 /list 里的序号。").toString();
    }

    public static String notFound(String arg, List<Device> devices) {
        return "❓ 没找到「" + arg + "」。\n\n" + deviceList(devices);
    }

    /** 唤醒成功：包数、目标设备、目的地址与端口、来源网段，以及ARP 回退说明（如有）。 */
    public static String wakeOk(Wake wake) {
        StringBuilder sb = new StringBuilder();
        sb.append("✅ 已发送 ").append(wake.packets).append(" 个魔术包 → ").append(wake.name)
                .append("（").append(wake.mac).append("）\n目标 ")
                .append(wake.target).append(" :").append(wake.port);
        if (wake.localCidr.length() > 0) {
            sb.append("\n来源 ").append(wake.localCidr);
        }
        if (wake.fallbackFrom.length() > 0) {
            sb.append("\n（").append(wake.fallbackFrom).append(" 解析不到 ARP，已改用广播 ")
                    .append(wake.fallbackTo).append("）");
        }
        return sb.toString();
    }

    public static String wakeFailed(String name, String error) {
        return "⚠️ 唤醒失败：" + error + "\n（目标：" + name + "）";
    }

    public static String apiError(String error) {
        return "⚠️ 飞书接口错误：" + error + "\n请检查 App ID / App Secret / 权限与可用范围。";
    }
}
