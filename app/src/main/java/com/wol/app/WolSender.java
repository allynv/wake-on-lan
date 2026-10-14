package com.wol.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * 发送 Wake-on-LAN 魔术包。
 *
 * <p>一次唤醒只发一个魔术包（可通过「每个目标发送次数」加大）：优先发给你配置的 IP（单播）；
 * 如果内核 ARP 解析不到它（电脑关机且网卡不回 ARP 时，单播包会被内核直接丢掉），
 * 就自动改发一个广播包 —— 广播不依赖 ARP，是唯一能保证包真的发到网上的方式。
 *
 * <p>另外通过 {@link Network#bindSocket} 把发送套接字绑定到 Wi-Fi，
 * 这样即使「Wi-Fi 无互联网 + 手机开着流量」，包也一定从 Wi-Fi 出口发出。
 */
public final class WolSender {

    /** 每次发送之间的间隔，避免瞬间打爆路由器。 */
    private static final int SEND_GAP_MS = 120;
    /** 无法算出子网广播时使用的全局广播地址。 */
    private static final String BROADCAST_LIMITED = "255.255.255.255";
    /** 单播发出后等待 ARP 解析完成的最长时间。 */
    private static final long ARP_WAIT_MS = 400L;
    /** 单播发出后第一次查邻居表前的等待（ARP 回应在局域网里是毫秒级）。 */
    private static final long ARP_FIRST_WAIT_MS = 150L;
    /** ip neigh 命令最多等待的时间。 */
    private static final long IP_NEIGH_TIMEOUT_MS = 1200L;
    /** 邻居表文件路径。 */
    private static final String ARP_TABLE = "/proc/net/arp";

    private WolSender() {
    }

    /** 当前 Wi-Fi 网络信息（用于界面展示）。 */
    public static final class WifiInfo {
        public boolean found;
        public Network network;
        public String ifName = "";
        public String ip = "";
        public int prefix = 24;
        public String broadcast = "";

        public String cidr() {
            return ip.length() == 0 ? "" : ip + "/" + prefix;
        }
    }

    /** 发送结果。 */
    public static final class Result {
        public boolean ok;
        public String error = "";
        public int packets;
        public int failedTargets;
        public final List<String> targets = new ArrayList<String>();
        public String portText = "";
        public boolean boundToWifi;
        public boolean hasWifi;
        public String localCidr = "";
        /** 单播无法解析 ARP 时改用广播：这里记录原目标与改用后的目标（为空表示没有回退）。 */
        public String fallbackFrom = "";
        public String fallbackTo = "";

        public String targetText() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < targets.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(targets.get(i));
            }
            return sb.toString();
        }
    }

    /**
     * 查询当前 Wi-Fi 网络（含本机 IPv4 与子网广播地址）。
     *
     * <p>说明：{@code getAllNetworks()} / {@code getActiveNetwork()} 已被 Google 标记为过时，
     * 但在 API 23+ 上它们仍是同步获取 Wi-Fi 链路信息最直接的方式；这里先用枚举、再退回活动网络，
     * 以兼容新老系统与各家 ROM（HyperOS 等）。
     */
    @SuppressWarnings("deprecation")
    public static WifiInfo getWifiInfo(Context context) {
        WifiInfo info = new WifiInfo();
        ConnectivityManager cm = (ConnectivityManager) context.getApplicationContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return info;
        }
        Network[] networks = null;
        try {
            networks = cm.getAllNetworks();
        } catch (Exception ignored) {
            // 个别 ROM 上可能抛异常，退回活动网络
        }
        if (networks != null) {
            for (Network network : networks) {
                if (fillWifiInfo(info, cm, network)) {
                    return info;
                }
            }
        }
        try {
            Network active = cm.getActiveNetwork();
            if (active != null && fillWifiInfo(info, cm, active)) {
                return info;
            }
        } catch (Exception ignored) {
            // 未连接任何网络
        }
        return info;
    }

    /** 若该网络是 Wi-Fi 且拿到 IPv4，则填充 info 并返回 true。 */
    private static boolean fillWifiInfo(WifiInfo info, ConnectivityManager cm, Network network) {
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return false;
        }
        LinkProperties lp = cm.getLinkProperties(network);
        if (lp == null) {
            return false;
        }
        LinkAddress v4 = firstIpv4(lp);
        if (v4 == null) {
            return false;
        }
        byte[] local = NetMath.toBytes(v4.getAddress().getHostAddress());
        if (local == null) {
            return false;
        }
        info.found = true;
        info.network = network;
        info.ifName = lp.getInterfaceName() == null ? "" : lp.getInterfaceName();
        info.ip = NetMath.toString(local);
        info.prefix = v4.getPrefixLength();
        byte[] bcast = NetMath.broadcastAddress(local, info.prefix);
        info.broadcast = bcast == null ? "" : NetMath.toString(bcast);
        return true;
    }

    /**
     * 发送魔术包。
     *
     * @return 结果对象；ok=false 时 error 为可展示的中文原因。
     */
    public static Result send(Context context, Device device, Prefs prefs) {
        Result result = new Result();
        int port = device.port > 0 ? device.port : prefs.defaultPort;
        result.portText = prefs.alsoPort7 && port != 7 ? (port + ", 7") : String.valueOf(port);

        byte[] packet;
        try {
            packet = WolPacket.build(device.mac);
        } catch (IllegalArgumentException e) {
            result.ok = false;
            result.error = "MAC 格式非法：" + device.mac;
            return result;
        }

        WifiInfo wifi = getWifiInfo(context);
        result.hasWifi = wifi.found;
        result.localCidr = wifi.cidr();
        if (!wifi.found) {
            result.ok = false;
            result.error = "未连接 Wi-Fi，无法发送唤醒包";
            return result;
        }

        // 只发一个魔术包：优先发给你配置的 IP（单播）。
        // 单播必须先由内核 ARP 解析出对方 MAC；电脑关机时若网卡不回 ARP，
        // 这个包会被内核直接丢掉（而 send() 仍然返回成功），所以解析不到时
        // 自动改用广播 —— 广播不依赖 ARP，是唯一能保证包真的出去了的方式。
        String unicast = device.hasIp() ? device.ip.trim() : "";
        String broadcast = wifi.broadcast.length() > 0 ? wifi.broadcast : BROADCAST_LIMITED;

        List<Integer> ports = new ArrayList<Integer>();
        ports.add(Integer.valueOf(port));
        if (prefs.alsoPort7 && port != 7) {
            ports.add(Integer.valueOf(7));
        }
        int repeat = prefs.repeat < 1 ? 1 : prefs.repeat;

        DatagramSocket socket = null;
        try {
            // 先用"未绑定"套接字，才能绑定到指定 Wi-Fi 网络（已绑定端口的套接字部分 ROM 会拒绝）
            socket = new DatagramSocket(null);
            socket.setBroadcast(true);
            if (wifi.network != null) {
                try {
                    wifi.network.bindSocket(socket);
                    result.boundToWifi = true;
                } catch (Exception firstAttempt) {
                    // 少数实现要求套接字已绑定本地端口，绑定后再试一次
                    try {
                        socket.bind(new InetSocketAddress(0));
                        wifi.network.bindSocket(socket);
                        result.boundToWifi = true;
                    } catch (Exception secondAttempt) {
                        // 两次都失败则退化为系统默认路由，继续尝试发送
                        result.boundToWifi = false;
                    }
                }
            }
            if (!socket.isBound()) {
                socket.bind(new InetSocketAddress(0));
            }

            if (unicast.length() > 0) {
                sendTo(socket, packet, unicast, ports, repeat, result, true);
                if (waitArpResolved(unicast, ARP_WAIT_MS)) {
                    result.targets.add(unicast);
                } else {
                    // 单播包已被内核丢弃，不计入实际发出的包数；
                    // 改用广播重发，保证这次唤醒真的有一个包发到网上。
                    result.packets = 0;
                    sendTo(socket, packet, broadcast, ports, repeat, result, true);
                    result.targets.add(broadcast);
                    result.fallbackFrom = unicast;
                    result.fallbackTo = broadcast;
                }
            } else {
                sendTo(socket, packet, broadcast, ports, repeat, result, true);
                result.targets.add(broadcast);
            }
        } catch (Exception e) {
            result.ok = false;
            result.error = e.getMessage() == null ? e.toString() : e.getMessage();
            return result;
        } finally {
            if (socket != null) {
                socket.close();
            }
        }

        if (result.packets == 0) {
            result.ok = false;
            result.error = "没有任何数据包发送成功，请检查是否已连接内网 Wi-Fi";
            return result;
        }
        result.ok = true;
        return result;
    }

    /** 向单个目标发送：每个端口重复 repeat 次。 */
    private static void sendTo(DatagramSocket socket, byte[] packet, String target,
                               List<Integer> ports, int repeat, Result result, boolean countPackets) {
        InetAddress address;
        try {
            address = InetAddress.getByName(target);
        } catch (Exception e) {
            result.failedTargets++;
            return;
        }
        for (Integer p : ports) {
            for (int i = 0; i < repeat; i++) {
                try {
                    socket.send(new DatagramPacket(packet, packet.length, address, p.intValue()));
                    if (countPackets) {
                        result.packets++;
                    }
                } catch (Exception sendFailure) {
                    result.failedTargets++;
                    break;
                }
                sleep(SEND_GAP_MS);
            }
        }
    }

    /**
     * 确认单播包的 ARP 是否已解析（解析成功才说明这个包真的发出去了）。
     *
     * <p>只查一两次：读邻居表可能要走 {@code ip neigh} 命令（一次进程开销），
     * 不适合高频轮询；而 ARP 回应在局域网里是毫秒级的，等 150ms 足够。
     */
    private static boolean waitArpResolved(String ip, long maxWaitMs) {
        sleep(ARP_FIRST_WAIT_MS);
        if (neighborEntryExists(ip)) {
            return true;
        }
        long remaining = maxWaitMs - ARP_FIRST_WAIT_MS;
        if (remaining > 0) {
            sleep(remaining);
            return neighborEntryExists(ip);
        }
        return false;
    }

    /**
     * 读本机邻居（ARP）表，判断该 IP 的 MAC 是否已解析出来。
     *
     * <p>依次尝试：{@code ip neigh show <ip>}（rtnetlink，部分系统用它才能读到）→
     * 直接读 {@code /proc/net/arp}。两条都不通时返回 false，调用方会改用广播，
     * 所以即使读不到表，唤醒也不会失败。
     */
    private static boolean neighborEntryExists(String ip) {
        Boolean viaCommand = ipNeighLookup(ip);
        if (viaCommand != null) {
            return viaCommand.booleanValue();
        }
        return procArpLookup(ip);
    }

    /** @return TRUE / FALSE 表示命令给出明确结论；null 表示命令不可用，应回退其它方式。 */
    private static Boolean ipNeighLookup(String host) {
        Process process = null;
        BufferedReader reader = null;
        try {
            ProcessBuilder builder = new ProcessBuilder("/system/bin/ip", "neigh", "show", host);
            builder.redirectErrorStream(true);
            process = builder.start();
            reader = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
            boolean found = false;
            String line;
            long deadline = System.currentTimeMillis() + IP_NEIGH_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline && (line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.length() == 0 || !trimmed.startsWith(host)) {
                    continue;
                }
                // REACHABLE / DELAY / PROBE / STALE 都会带 lladdr；INCOMPLETE / FAILED 不会
                if (trimmed.contains("lladdr")) {
                    found = true;
                    break;
                }
            }
            if (waitForExit(process) != 0 && !found) {
                return null;
            }
            return Boolean.valueOf(found);
        } catch (Exception e) {
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                    // 忽略
                }
            }
            if (process != null) {
                process.destroy();
            }
        }
    }

    /** 直接读 /proc/net/arp（部分新系统会通过 SELinux 屏蔽该文件）。 */
    private static boolean procArpLookup(String host) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(ARP_TABLE));
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (header) {
                    header = false;
                    continue;
                }
                String trimmed = line.trim();
                if (trimmed.length() == 0 || !trimmed.startsWith(host)) {
                    continue;
                }
                String[] cols = trimmed.split("\\s+");
                if (cols.length >= 4 && cols[0].equals(host)) {
                    return cols[2].startsWith("0x2");
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                    // 忽略
                }
            }
        }
    }

    /** 等进程退出，最多等 300ms；超时返回 -1。 */
    private static int waitForExit(Process process) {
        long deadline = System.currentTimeMillis() + 300L;
        while (System.currentTimeMillis() < deadline) {
            try {
                return process.exitValue();
            } catch (IllegalThreadStateException e) {
                sleep(20L);
            }
        }
        return -1;
    }

    private static LinkAddress firstIpv4(LinkProperties lp) {
        List<LinkAddress> list = lp.getLinkAddresses();
        if (list == null) {
            return null;
        }
        for (LinkAddress la : list) {
            if (la.getAddress() instanceof Inet4Address) {
                return la;
            }
        }
        return null;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
