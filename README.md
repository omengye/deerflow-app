# DeerFlow Android

原生 Kotlin / Jetpack Compose 客户端，直接连接 DeerFlow 当前分支的 **Waku App Server v10**。手机与桌面使用同一服务端会话；Agent 在服务器运行，关闭手机连接不会发送取消命令。

此版本不再调用 AG-UI、旧模型 REST 或旧技能审批 REST，不提供双后端切换。已有本地聊天记录保留为只读历史，不会自动上传或重发。

接入取舍、Codex / Claude Code 参考及协议映射见 [架构说明](docs/waku-architecture.md)。

## 连接

在设置中填写：

- **服务器地址**：例如 `wss://deerflow.example.com/v1`。Android 模拟器访问宿主机可使用 `ws://10.0.2.2:18787/v1`。
- **Token**：服务器的 Waku daemon token，通过 WebSocket 首条 `hello` 认证。设备端使用 Android Keystore AES-GCM 加密保存。
- **服务器工作目录**：例如 `/workspaces`；这是服务器上的路径。
- **模型 ID**：可选，填写服务器已配置的模型 ID；留空使用服务端默认值。

协议版本不匹配或认证被拒绝时停止自动重试，修正设置后重新连接。原 AG-UI 地址和凭据需要重新配置。公网部署使用 WSS；当前 daemon token 拥有服务端控制权限，应只提供给可信设备。

## 弱网与恢复

- 连接握手超时为 15 秒，WebSocket 每 20 秒检测心跳。
- 可恢复连接错误使用有上限的指数退避与随机抖动；离线暂停尝试，网络恢复、Wi-Fi/移动网络切换和返回前台时重新同步。
- 恢复先读取会话、附加现存 runtime，再按 epoch / runtime / sequence 分页补齐事件；连接恢复不会重新执行 prompt 或替换运行中的 Agent。
- 会话内容、流式片段、游标和待核对操作保存到同一原子快照，按服务器与凭据隔离。
- 输入草稿与附件引用按会话持久保存；本地写入成功后才清空输入框。附件权限失效时明确提示重新选择。
- 发消息、取消和审批均不自动重发。请求可能已被服务器执行但回执丢失时，先读取状态；不能确认时展示原文和待核对状态，避免重复写入或工具执行。
- 取消回执只代表命令已接收；最终停止状态以执行事件为准。
- 手机进入后台可以断开订阅，任务继续在服务器运行。本版不依赖前台服务维持长任务，也未增加系统推送。

服务端重启不等于普通断线。当前 Waku 重放日志只覆盖存活的 runtime / daemon；客户端使用持久历史核对，并保留无法证明的操作状态，不承诺跨服务器重启的 exactly-once 执行。当前 v10 也未提供统一的待审批快照及跨端审批完成事件，客户端不会捏造已经恢复或已批准的状态。

## 功能

- 服务端会话列表和历史、流式文本/推理/工具活动、独立工具审批与用户问题。
- 文件和图片上传到服务器托管存储，通过结构化附件引用发送，不拼接 Android 专属提示词。
- 技能提案通过 Waku 管理 RPC 读取和审查，携带提案版本与基础内容摘要，区分批准与发布结果。
- 文件预览和下载不向外部链接发送 Waku 凭据。

## 构建与验证

需要 Android SDK 36、build-tools 36.0.0、Gradle 9.5.1 和兼容 JDK。项目编译目标为 Java 17，最低 Android 11（API 30）。在本机 `local.properties` 配置 `sdk.dir`。

```sh
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

如使用本机 Gradle wrapper，可将 `gradle` 替换为 `./gradlew`（Windows 为 `gradlew.bat`）。CI 会安装指定版本的 Gradle，不依赖本地未提交的 wrapper 启动脚本。

测试包含握手与认证、旧 socket 回调隔离、超时/断线结果分类、事件缓冲溢出、重放顺序与边界、稳定消息 ID、进程恢复快照及配置/提案契约。真实设备弱网和真实服务端联调按 [验收清单](docs/waku-mobile-validation.md) 执行。

## 代码结构

- `data/waku/`：协议、WebSocket 客户端、会话投影、原子缓存、恢复规则。
- `data/ConversationRepository.kt`：连接生命周期、会话同步、用户命令与待核对状态。
- `data/settings/`：连接配置与加密凭据。
- `data/proposal/`：版本校验的技能治理 RPC。
- `domain/` 与 `ui/`：展示模型、旧记录只读解析、Compose 界面。

GitHub Actions 产出 debug APK。带 `v` 前缀的 tag 按既有 workflow 和仓库 signing secrets 构建发布包。