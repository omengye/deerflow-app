# 附件与 OSS：2.3.1

本次沿用 Waku v12。生成文件集中在当前会话顶部“产物”页面；用户新发送的图片和文件显示在其消息气泡内。不重建旧历史附件，不将 Agent 产物上传 OSS。

## 使用

1. 安装 `dist/deerflow-2.3.1.apk`（同 `dist/deerflow-2.3.0-oss.apk`），沿用原 Waku 连接配置。
2. 进入“设置 → OSS 附件存储”，导入单独提供的 `oss-profile.json` 后保存。该配置包含账号密钥，需要通过本地方式传入手机；密钥不会打入 APK、源码或服务端技能。设备使用 Android Keystore AES-GCM 加密保存。
3. 在新会话选择图片或文件后，选择“服务器”或“OSS”。默认服务器；同一草稿后续新增附件沿用已选方式。
4. OSS 发送前先确认服务端已加载下述技能。Agent 下载附件后，图片通过 `view_image` 查看，文档按实际格式解析。

| 路径 | 限制 | 读取方式 |
| --- | --- | --- |
| 服务器附件 | 每个 8 MiB，每条总计 16 MiB | 原生 Waku 附件，不需要 OSS 技能 |
| OSS 附件 | 每个 50 MiB，图片 20 MiB，每条总计 100 MiB | App 直传 S3，Agent 下载并校验 |
| 所有消息附件 | 每条最多 8 个 | 新消息按稳定 ID 保存归属 |
| 生成产物 | 单次最多罗列 64 个，每次单文件下载上限 64 MiB | 服务端任务产物列表及分块读取 |

服务端仍会消耗从 OSS 下载输入文件的入站流量；App 上传和再次查看 OSS 附件时不经过会话服务器。清单链接有效期默认 24 小时；过期后需重新提交附件，App 自己查看时使用当前密钥生成新链接。输入对象位于 `deerflow-app/attachments/<随机 ID>/`，业务附件不会自动删除；可由桶生命周期管理清理策略。

## 配套服务端

代码位于 `D:/Tools/deerflow-api/skills/custom/oss-attachments/`，交付包为 `dist/oss-attachments.zip`，只包含 `SKILL.md`、`skill-runtime.json` 和下载脚本。

将 `oss-attachments` 文件夹放入运行实例实际配置的技能根目录 `custom/` 下。该实例若直接使用此项目的配置，需使以下内容生效：

```yaml
skills:
  enabled: true
  path: ./skills
```

本地 `D:/Tools/deerflow-api/config.yaml` 的旧技能路径不存在，已改成 `./skills`。此文件属于忽略的本地运行配置，不打入交付包。若线上使用另一个 YAML、桌面生成配置或 `--skills-config`，需在该实际来源注册并启用技能，不能只复制到未绑定的目录。

原生启动器会扫描已显式配置的 `public/custom`，将未显式禁用的技能安装并启用。部署后按实例的正常流程重新加载配置/启动服务，再新建会话，使本轮技能快照包含 `oss-attachments`。本次仅修改本地代码和配置，没有重启或部署运行中的服务。

当前下载脚本只允许 HTTPS `s3.cstcloud.cn`、指定桶和 App 输入对象前缀。必须携带 `User-Agent: S3Drive`，普通浏览器直接打开签名链接可能返回 401。App 与 Agent 下载时均携带该标识；TLS 证书验证保持开启，禁止重定向。

## 验证

- JVM 覆盖独立签名向量、Unicode 对象名、临时 Token、清单、普通附件读取、缓存边界与完整性校验、消息 ID 归属及重启/历史恢复、产物列表不读取文件正文。
- 真实 S3 验证同时使用 Kotlin App 实现与 Agent Python 下载脚本，创建随机测试对象、核对字节与摘要、删除测试对象。
- Android 模拟器独立验证系统 TLS、直接上传/冷缓存下载、Android Keystore 配置保存；UI 使用明确的测试会话验证附件气泡、独立产物页和缓存保存，不代表实际 Agent 会话已在线联调。
- 实测服务器技能发现及 Python 运行声明；未向在线 Agent 提交真实业务消息。

精确结果见 `attachments-oss-validation-20261031.json`。APK 沿用此前调试签名，可覆盖同签名版本安装；不是正式商店发布签名。
