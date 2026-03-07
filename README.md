# NearLink Mesh Gateway App

基于 BLE 的星闪（NearLink）SLE Mesh 网络调试与控制工具，适用于 Android 平台。

通过 BLE 连接任意一块运行网关固件的 Hi3863 开发板，即可查看整个 Mesh 网络拓扑、向指定节点发送文本/图片，或向多节点进行组播图片传输。

---

## 功能概览

- **网关扫描**：自动发现所有 `SLE_GW_XXXX` BLE 网关设备，显示名称、MAC 地址和信号强度
- **一键连接**：点击任意网关即可建立 BLE GATT 连接，连接后自动查询拓扑
- **拓扑可视化**：网格展示所有 Mesh 节点，区分网关（0 跳）、直连（1 跳）和多跳路由节点
- **单播文本**：点击节点卡片，弹出对话框输入文本后定向发送
- **单播图片**：选图 → 裁剪 → 预览 → 选择分辨率与传输模式后发送到单个节点
- **组播图片**：长按节点进入多选模式，一次性将图片广播到最多 8 个节点
- **全网广播**：底部输入框一键向所有节点广播文本消息
- **传输进度**：实时显示图片上传进度、Mesh 层流控进度、组播各节点完成状态
- **拓扑自动刷新**：连接后自动查询拓扑；每 30 秒定时刷新；图片传输结束后自动刷新
- **节点图片记忆**：每个节点最近一次发送的图片持久化保存，下次操作时可预览历史图
- **通信日志**：实时滚动显示上下行记录，带时间戳和颜色区分

## 页面结构

| 扫描页 | 已连接页 |
|--------|---------|
| 扫描并列出所有 Mesh 网关 | 拓扑网格 + 节点操作 + 图片传输 + 通信日志 |

## 系统要求

- Android 8.0（API 26）及以上
- 支持 BLE 4.0+ 的设备
- 需授予蓝牙扫描、连接和定位权限

## 硬件配合

本 App 配合海思 Hi3863 星闪开发板使用，固件需包含以下模块：

| 固件模块 | 说明 |
|---------|------|
| `mesh_main.c` | SLE Mesh 组网核心，HELLO 路由学习 |
| `mesh_transport.c` | SLE 多连接传输层（1 主 8 从） |
| `mesh_forward.c` | 单播转发与广播洪泛 |
| `mesh_route.c` | 路由表管理 |
| `ble_gateway.c` | BLE GATT Server，桥接手机与 Mesh，含图片流控引擎 |
| `image_receiver.c` | 目标节点图片接收与 CRC 验证 |

每块开发板同时运行 SLE（星闪近场通信）和 BLE 双协议栈，SLE 用于板间 Mesh 组网，BLE 用于手机连接。

## 通信协议

App 与网关之间通过 BLE GATT 自定义 Service（UUID `0xFFE0`）通信。

### GATT 特征

| UUID | 方向 | 说明 |
|------|------|------|
| `0xFFE1` | Notify | 网关 → 手机（上行数据） |
| `0xFFE2` | Write | 手机 → 网关（下行指令） |

### 下行命令（手机 → 网关）

| 命令码 | 帧格式 | 说明 |
|--------|--------|------|
| `0x01` | `AA 01 DST(2) LEN PAYLOAD` | 单播到指定节点 |
| `0x02` | `AA 02 FF FF LEN PAYLOAD` | 广播到所有节点 |
| `0x03` | `AA 03` | 查询 Mesh 拓扑 |
| `0x04` | `AA 04 DST(2) TOTAL(2) PKT(2) W(2) H(2) MODE XFER` | 图片传输 START（14 字节） |
| `0x05` | `AA 05 DST(2) SEQ(2) LEN PAYLOAD` | 图片数据分包 |
| `0x06` | `AA 06 DST(2) CRC(2)` | 图片传输 END |
| `0x07` | `AA 07 DST(2)` | 取消图片传输 |
| `0x0A` | `AA 0A N ADDR1(2)...ADDRn(2) TOTAL(2) PKT(2) W(2) H(2) MODE` | 组播图片 START |

> `XFER` 字段：`0x00`=FAST 模式（网关缓存后流控），`0x01`=ACK 模式（逐包确认后立即注入）

### 上行通知（网关 → 手机）

| 命令码 | 帧格式 | 说明 |
|--------|--------|------|
| `0x81` | `AA 81 SRC(2) LEN PAYLOAD` | 节点发来的数据 |
| `0x83` | `AA 83 GW(2) COUNT [ADDR(2) HOPS]×N` | 拓扑响应 |
| `0x85` | `AA 85 SRC(2) STATUS SEQ(2)` | 图片分包 ACK |
| `0x86` | `AA 86 SRC(2) STATUS` | 图片传输结果（0=成功，1=OOM，2=超时，3=取消，4=CRC 错误） |
| `0x87` | `AA 87 SRC(2) MISS_HI MISS_LO BITMAP[30]` | 缺包位图（网关自主补包） |
| `0x89` | `AA 89 SRC(2) PHASE RX(2) TOTAL(2)` | 网关流控进度（PHASE: 0=首轮，1=补包） |
| `0x8A` | `AA 8A DONE TOTAL ADDR(2) STATUS` | 组播单节点完成通知 |

> `0x88`（CHKPT_ACK）由网关内部处理，不转发给 App。

## 图片传输流程

### FAST 模式（默认，推荐）
```
App                          网关                         目标节点
 │──START(XFER=0)────────────→│                              │
 │──DATA×N(5ms/包)────────────→│ ← BLE 高速上传到网关缓存      │
 │──END(CRC)──────────────────→│                              │
 │                             │──流控注入 Mesh (分段+CHKPT)──→│
 │←──0x89 Progress(实时)───────│                              │
 │←──0x86 Result(成功/失败)────│←──────────────────────────── │
```

### ACK 模式（逐包确认）
```
App ──START(XFER=1)──→ 网关 ──立即注入 Mesh──→ 目标节点
App ──DATA(seq=0)────→ 网关 ←──0x85 ACK───────
App ──DATA(seq=1)────→ 网关 ... (重复)
App ──END─────────────────────────────────────→
                             ←──0x86 Result───
```

### 组播模式
- App 发送 `0x0A MCAST_START`，随后以广播地址（`0xFFFF`）上传数据
- 网关广播完整数据到所有目标节点
- 每个节点完成后，网关发送 `0x8A` 通知 App
- App 汇总所有节点状态，最终进入 `MulticastDone`

## 图片处理参数

| 参数 | 值 |
|------|-----|
| 取模方式 | 水平方向，LSB 优先 |
| 支持分辨率 | 240×360、480×800 |
| BLE 分包大小 | 200 字节/包 |
| 每包间隔（FAST） | 5 ms |
| 图片格式 | 黑白位图（1bpp），压缩后上传 |
| 校验 | CRC-16/CCITT |

## 项目结构

```
MeshGatewayApp/
├── app/src/main/
│   ├── AndroidManifest.xml
│   └── java/com/meshgateway/
│       ├── MainActivity.kt      # Compose UI（扫描页、连接页、裁剪、预览、组播选择）
│       ├── BleManager.kt        # BLE 管理、图片发送引擎、状态机
│       └── MeshProtocol.kt      # 协议帧编解码、数据模型、CRC-16
├── app/build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
└── gradle/
```

### 核心模块说明

**`BleManager.kt`** — BLE 通信与图片发送引擎

- 扫描过滤 `SLE_GW_` 前缀设备，按名称去重（解决多板共享 MAC 问题）
- GATT 连接、MTU 协商（247 字节）、服务发现、CCCD 订阅
- BLE 串行写入队列（`writeQueue`），保证多包顺序不乱
- 图片发送状态机：`Idle → Sending → MeshTransfer/MulticastTransfer → Done/MulticastDone/Cancelled`
- FAST 模式：5ms/包高速上传到网关，网关自主流控注入 Mesh
- ACK 模式：逐包等待 `0x85` 确认后发下一包
- 组播模式：同时向最多 8 个目标节点传输同一张图片
- 动态超时：`30s + 0.5s×包数 + 10s×组播目标数`

**`MeshProtocol.kt`** — 协议编解码

- 下行帧构造：`buildUnicast()` / `buildBroadcast()` / `buildTopoQuery()` / `buildImageStart()` / `buildImageData()` / `buildImageEnd()` / `buildImageCancel()` / `buildImageMulticastStart()`
- 上行帧解析：`parseNotification()` → `Topology` / `DataFromNode` / `ImageAck` / `ImageResult` / `ImageMissing` / `ImageProgress` / `MulticastProgress`
- CRC-16/CCITT 计算：`crc16()`

**`MainActivity.kt`** — Jetpack Compose UI

- `ScanPage`：扫描按钮 + 设备列表
- `ConnectedPage`：拓扑网格 + 图片进度条 + 广播输入 + 通信日志
- `NodeActionDialog`：节点操作选择（发送文本 / 发送图片 / 查看历史图）
- `CropImageDialog`：图片裁剪，支持拖动调整裁剪区域
- `ImagePreviewDialog`：发送前预览，选择分辨率与传输模式（FAST/ACK）
- `NodeImageStore`：节点图片持久化（按节点地址存储到应用私有目录）
- 连接后自动查询拓扑（`LaunchedEffect(cccdReady)`），每 30 秒定时刷新
- 图片传输完成/取消/超时后，自动触发拓扑刷新，结果卡片展示 3 秒后消失
- 长按节点进入组播多选模式，再次长按或取消选中可退出

## 技术栈

| 组件 | 版本 |
|------|------|
| Kotlin | 1.9.x |
| Jetpack Compose | BOM 2024.08 |
| Material 3 | Compose Material3 |
| minSdk | 26 (Android 8.0) |
| targetSdk | 35 (Android 15) |
| 构建工具 | Gradle Kotlin DSL |

## 构建

```bash
# 克隆项目
git clone <repo_url>
cd MeshGatewayApp

# Android Studio 打开后自动同步 Gradle

# 或命令行构建
./gradlew assembleDebug

# 输出 APK
# app/build/outputs/apk/debug/app-debug.apk
```

## 使用流程

1. 将 Mesh 固件烧录到各 Hi3863 开发板并上电
2. 等待各板完成 SLE Mesh 组网（约 10 秒）
3. 打开 App，点击「扫描 Mesh 网关」
4. 从列表中选择一个 `SLE_GW_XXXX` 设备连接
5. App 自动查询拓扑，网格中显示所有 Mesh 节点
6. **发送文本**：点击节点卡片 → 选择「发送文本」→ 输入内容发送
7. **发送图片（单播）**：点击节点卡片 → 选择「发送图片」→ 选图 → 裁剪 → 选择分辨率/模式 → 发送
8. **发送图片（组播）**：长按节点进入多选模式 → 勾选多个节点 → 点击「组播发图」→ 选图 → 裁剪 → 发送
9. 传输完成后 App 自动刷新拓扑

## 设计要点

**按名称去重扫描**：各开发板 BLE MAC 由 Mesh 地址派生（`CC:BB:AA:00:XX:XX`），App 扫描列表按 `SLE_GW_XXXX` 名称去重，确保多板场景下每块板独立显示。

**连接后自动校准**：用户扫描页点击的设备名可能与实际连接的网关不一致（BLE 广播竞争），连接成功后 App 自动发送拓扑查询，用返回的实际网关地址更新显示名。

**BLE 串行写队列**：所有 BLE 写操作经过 `writeQueue` 串行化，等前一个 `onCharacteristicWrite` 回调后才发下一包，防止 Android BLE 并发写入乱序。

**网关流控卸载**：FAST 模式下 App 只负责快速将数据上传到网关缓存，Mesh 层的分段、超时重传、CHKPT 确认、补包等全部由固件侧 `ble_gateway.c` 流控引擎处理，App 仅接收进度通知。

**传输后自动刷新拓扑**：图片传输完成/超时/取消后，在结果卡片展示期间（3 秒）自动触发拓扑查询，刷新完成时卡片才消失，确保拓扑节点状态及时更新。

**UTF-8 全支持**：数据载荷支持中文等 Unicode 字符，日志区自动判断可打印文本或回退 Hex 显示。

## License

MIT