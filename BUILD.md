# 构建与实现说明

本文件面向**自行编译**或**二次开发**的人。普通安装与使用请看 [README.md](README.md)。

---

## 1. 环境要求

- Windows + PowerShell 5.1+
- 本机已有完整 JDK 17 时会直接复用（必须包含 `jdk.zipfs` 模块）；否则自动从清华 TUNA 镜像下载 OpenJDK 17。
- Android 构件（build-tools r34 / platform-34）来自腾讯云 `mirrors.cloud.tencent.com/AndroidSDK/`，失败时回退 `dl.google.com`。

项目**不依赖 Gradle / Android Studio / AndroidX**，用 `aapt2 + javac + d8 + zipalign + apksigner`
串成一条最小编译流水线，首次运行会下载所需构件（约 250MB，缓存在 `.toolchain/`，已 gitignore）。

## 2. 构建

```powershell
# 在仓库根目录执行
powershell -ExecutionPolicy Bypass -File tools\build.ps1

# 指定版本号
powershell -ExecutionPolicy Bypass -File tools\build.ps1 -VersionName 1.1.0 -VersionCode 2

# 只下载 / 校验工具链
powershell -ExecutionPolicy Bypass -File tools\toolchain.ps1
```

产物：`dist\WolWake-<版本>.apk`

## 3. 签名证书

`keys/wol-release.jks` **不入库**（`.gitignore` 排除）。首次构建时 `build.ps1` 会用 `keytool`
自动生成一个自签名证书并复用它签名，所以克隆仓库后可以直接构建。

- **换一台机器构建出的证书不同**，签名不一致的 APK **无法覆盖安装**到已有安装上（需先卸载）；
  想保持一致，就把原来那台的 `keys/wol-release.jks` 拷过来。
- 该证书仅用于自用侧载，**不是上架密钥**。
- 证书口令目前写在 `build.ps1` 里（`$ksPass`）。密钥文件本身不入库，单有口令无法签名；
  但**不要**再把该 `.jks` 提交到公开仓库，否则口令与密钥就都齐了（Android 允许同签名覆盖安装，
  等于别人能签出你 App 的"升级包"）。

## 4. 单元测试

纯逻辑层（不依赖 Android 框架）的测试用例在 `tools/jvmtest/`，共 134 项：

```powershell
powershell -ExecutionPolicy Bypass -File tools\test-packet.ps1
```

| 测试类 | 覆盖内容 | 断言数 |
| --- | --- | --- |
| `WolPacketTest` | 魔术包构造、MAC 解析/校验、IPv4 与子网广播计算 | 33 |
| `FeishuCommandTest` | 飞书指令解析、设备名匹配（精确 / 模糊 / 歧义 / 序号） | 41 |
| `FeishuTextsTest` | 回复文案排版、通用小工具、错误分类 | 60 |

## 5. 构建期的两道自检

`tools\build.ps1` 在编译前会自动检查，避免「APK 能构建但装不上 / 一用就崩」：

1. `check-layouts.ps1`：扫描全部布局，任何视图（含其引用的 style）缺少 `layout_width` / `layout_height`
   立即中止构建 —— 否则运行时 inflate 会闪退。
2. `resources.arsc` 必须为 STORED 且 4 字节对齐 —— 否则 Android 11+ 直接拒绝安装，构建末尾会自检并报错。

## 6. 目录结构

```
wake-on-lan/
├─ app/src/main/
│  ├─ AndroidManifest.xml
│  ├─ java/com/wol/app/
│  │  ├─ MainActivity.java        主界面：列表 / 状态面板 / 发唤醒包 / 设置
│  │  ├─ DeviceEditActivity.java  添加、编辑设备
│  │  ├─ DeviceAdapter.java       列表适配器
│  │  ├─ Device.java              设备模型
│  │  ├─ DeviceStore.java         设备持久化（SharedPreferences）
│  │  ├─ Prefs.java               全局设置
│  │  ├─ WolSender.java           发送核心：绑定 Wi-Fi、构造目标、发 UDP
│  │  ├─ WolPacket.java           魔术包构造（纯 Java，可单测）
│  │  ├─ MacAddress.java          MAC 解析 / 校验 / 格式化（纯 Java，可单测）
│  │  ├─ NetMath.java             IPv4 与子网广播计算（纯 Java，可单测）
│  │  ├─ Texts.java               时间 / 数字小工具（纯 Java，可单测）
│  │  ├─ FeishuCommand.java       飞书指令解析与设备匹配（纯 Java，可单测）
│  │  ├─ FeishuTexts.java         飞书回复文案（纯 Java，可单测）
│  │  ├─ FeishuStatus.java        状态行文案（主界面与设置页共用）
│  │  ├─ FeishuErrors.java        错误分类：区分「需要处理」与「临时故障」（纯 Java，可单测）
│  │  ├─ FeishuApi.java           飞书接口客户端（HttpURLConnection + org.json）
│  │  ├─ FeishuPrefs.java         飞书配置与运行状态
│  │  ├─ FeishuPollService.java   前台服务：轮询指令 → 发唤醒包 → 回复
│  │  ├─ FeishuSettingsActivity.java  飞书配置界面
│  │  └─ BootReceiver.java        开机 / 覆盖安装后自动拉起轮询服务
│  └─ res/                        布局、颜色、样式、图标（含自适应图标）
├─ tools/
│  ├─ toolchain.ps1               下载并缓存 JDK + Android SDK 构件
│  ├─ build.ps1                   一键构建并签名 APK
│  ├─ check-layouts.ps1           静态检查布局参数
│  ├─ test-packet.ps1             纯逻辑层单元测试（134 项）
│  ├─ gen-icon.ps1                生成启动图标 PNG
│  ├─ ZipBuilder.java             组装 APK（保证 resources.arsc 为 STORED）
│  └─ jvmtest/                    单元测试用例
├─ keys/                          自签名证书（不入库，首次构建自动生成）
└─ dist/                          构建产物 APK（不入库）
```

## 7. 实现要点

- **魔术包**：`6 × 0xFF + 16 × MAC = 102 字节`（`WolPacket`）。
- **广播地址**：从 `ConnectivityManager.getLinkProperties()` 取本机 IPv4 与前缀长度，按 `ip | ~mask`
  通用计算，/16、/23、/25 都正确（`NetMath.broadcastAddress`）。
- **发送**（`WolSender.send`）：`DatagramSocket`（未绑定创建）+ `setBroadcast(true)` +
  `Network.bindSocket(socket)` 绑定 Wi-Fi 出口，`repeat` 次（默认 1）、间隔 120ms。目标选择：
  1. 填了 IPv4 → 先发单播给它；
  2. 等 150ms 后查本机邻居表（`ip neigh show <ip>`，失败回退 `/proc/net/arp`）确认该单播包真的发出去了；
  3. 解析不到 → 该单播包已被内核丢弃，改发一个广播包（设备旧 `broadcast` 覆盖值 → 本机网段广播 →
     `255.255.255.255`），并在结果里带上 `fallbackFrom` / `fallbackTo` 供界面提示。

  > 为什么需要第 2 步：单播要求内核先用 ARP 解析出目标 MAC。电脑关机、网卡不回 ARP 时，内核会
  > 直接丢掉这个包，而 `send()` 仍返回成功。这一步不是「检测电脑在不在」，而是确认包真的离开了手机。
- **没有任何检测代码**：不 ping、不扫描网段、不上报数据；只在点击卡片或收到指令时发送一个魔术包。
- **飞书远程唤醒**：`FeishuPollService`（前台服务）每 N 秒调一次 `GET /open-apis/im/v1/messages`
  拉取机器人单聊的新消息 → `FeishuCommand` 解析 → 复用 `WolSender` 发包 →
  `POST /open-apis/im/v1/messages` 回复结果。单聊的 `chat_id` 通过「给用户 open_id 发消息，从响应里
  取回 `data.chat_id`」自动获得。轮询带**持久化时间游标 + message_id 去重 + 10 分钟追赶上限**，
  所以重启不会重放历史指令。
  - 为什么用轮询而不是事件订阅：飞书的事件订阅 / 长连接只有官方 SDK（Go/Java/Python/Node）支持，
    Android 端自行实现风险太大；而历史消息接口限频 1000 次/分钟，10 秒一次（6 次/分钟）毫无压力，
    代价只是秒级延迟。
- **持久化**：`SharedPreferences` + `org.json`（Android 框架自带），无第三方依赖。

## 8. 已知限制

- **不做在线状态显示**：刻意的设计取舍 —— 唤醒只需要一个魔术包，状态检测既不可靠也不必要。
- 定时唤醒、快捷磁贴、桌面小组件、导入导出、批量唤醒**未实现**；定时唤醒在 HyperOS 上还需要
  「自启动 + 无限制省电」，且仍可能被系统限制。
- 跨网段 / 跨 VLAN 唤醒需要路由器支持定向广播转发（或改用中继），不在本版本范围。
- 发送次数默认为 1；若目标机网卡对丢包敏感，可在设置里调到 3~5。
- **飞书远程唤醒**：轮询有秒级延迟（默认 10 秒，最快 5 秒）；依赖手机后台常驻；`App Secret` 明文存在本机。

## 9. 完整排错（错误码表）

程序把飞书返回的错误分成两类，只有**需要用户动手**的那类才会推到飞书会话里：

| 类别 | 例子 | 行为 |
| --- | --- | --- |
| **需要处理** | `99991672` 权限未开通、`99991664` Secret 变了、`232034` 应用未启用、`230013` 不在可用范围 | 在会话里提醒一次（同一故障不重复刷屏），改好后自动恢复 |
| **临时故障** | `请求失败：timeout`（网络超时）、`code=2200 Internal Error`（飞书服务端偶发） | 只在 App 内与通知里显示，不打扰用户；程序自行重试 |

> 若手机连续 15 分钟连不上飞书，才会提醒一次「手机连不上飞书」，恢复后补一条「已恢复」。

| 错误 / 现象 | 原因与处理 |
| --- | --- |
| `code=232034` | 应用未在租户内启用：去飞书工作台启用一次 |
| `code=230013` | 用户不在应用可用范围内：应用发布 → 可用范围 → 加上自己 → 重新发布 |
| `code=230006` | 未添加「机器人」能力，或添加后没重新发布版本 |
| `code=230027` / `code=99991672` | 权限未开通，或开通后没重新发布版本 |
| `code=230053` | 用户在飞书里关闭了该机器人的消息，重新允许即可 |
| `code=99991664` / `code=99991663` | App Secret 被重置了：复制当前 Secret 填回 App 保存 |
| `code=230002` | 机器人不在该会话中 |
| `code=230034` | 接收者 ID 无效（`open_id` / `chat_id` 不匹配） |
| 指令发出后很久没反应 | 手机不在内网 / 没网，或服务被系统清理（看常驻通知是否还在） |
| 换手机 | 新设备上重新「连接并获取会话」即可；`chat_id` 不变 |
| 想抓崩溃日志 | 开发者选项打开 USB 调试后 `adb logcat -b crash`；或拨号盘输入 `*#*#284#*#*` 生成 bugreport |
