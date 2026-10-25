# 轻衡 1.8.1 发布构建

日期：2026-10-17。本次在既有 D 盘工程中采用用户选定并再次附图确认的第 5 张应用图标；同时整理待用户审阅的 GitHub 文案和 QQ 群公告。1.8 的完整构建记录保存在 [BUILD_STATUS_1.8.md](BUILD_STATUS_1.8.md)。

## 产物与版本

- 版本：**1.8.1 / versionCode 10**。
- 包名：**com.fitledger**，与已交付旧版本相同。
- APK：`FitLedger-v1.8.1-update.apk`，**52,421,130 字节**，约 52.42 MB / 49.99 MiB，release、非 debuggable。
- APK SHA-256：`a71c091ba8c2ce2ff3a6a018d69084d777c74d668ec64fb6b7523dd0f3f4f7ee`。
- 源码：`FitLedger-v1.8.1-source.zip`，包含图标原图、512px 导出、裁切预览、资源导出脚本与全部待审阅文档；不包含原签名私钥、用户备份、构建缓存或安装包。
- 最低 Android 8.0 / API 26，目标 API 35。

## 图标

采用用户最后附图的原始 PNG，1254×1254，原文件 SHA-256 为 `7537403b0135a2291450708a3a79067805ca274b261f2998730ff73c9636ff5d`。保存在 `artwork/launcher-icon-source.png`，没有重新绘制角色或修改构图。

`scripts/export-launcher-icons.ps1` 将该图缩放到 108dp 自适应图标画布的中心 68dp，并延展边缘背景以适配遮罩及动画。实际打包位图为 432×432；通过 APK 资源表定位到压缩后的资源路径，再逐像素核对，确认与导出资源完全相同。圆形和圆角方形预览已人工查看，角色、汤碗和哑铃均在画面内。常规图标与 `roundIcon` 都指向新图。

未把完整不透明彩色图片当作单色图层，避免主题图标显示成实心方块；本版没有提供专用 monochrome 图层。实际桌面遮罩、动画与主题处理仍以各手机系统为准，未作真机验证。

## 数据兼容与签名

**Room 仍为 v6，备份仍为 v9，本次没有新增 Migration。** 数据库 `ledger.db`、DataStore `settings` 和完整 1→6 迁移链保持原样。打包前后核对了 46 个业务源码及 Schema 文件的 SHA-256，全部相同；没有修改身体、食品、摄入、运动、计划、DailyTarget、偏好或提醒的实现。

既有 20 份 APK／源码归档逐一核对哈希，全部保留原样。新发布包使用工程外的原更新密钥，构建和 `apksigner` 验证通过；打包脚本对输出目录中包括新包的 11 份 APK 核对相同包名和证书，证书 SHA-256：

```text
45bbe006cb3504fbb0e422289f4c04ab433f4e226d9985101968f5312e7b7af0
```

核对的是此前交付的 APK 文件，未直接读取手机上的已安装证书。若手机安装的正是此前交付包，可以按同包名、同签名覆盖升级。安装前请先导出备份并另存；直接选择「更新／安装」，**不要卸载旧 App 或清除应用数据**。若系统报告签名冲突，请保留旧安装并反馈，不用卸载绕过。

## 检查结果

执行 `testDebugUnitTest lintDebug compileDebugAndroidTestKotlin assembleRelease assembleDebugAndroidTest --offline`，构建成功，用时 2 分 22 秒。

- **181 项主机自动化测试通过**，0 失败、0 错误、0 跳过；包括原有领域计算、备份、真实 SQLite 迁移和偏好持久化测试。
- Android Lint：**0 错误、6 个警告、1 个信息提示**。警告包括已有 Compose 命名、资源目录与 KAPT 建议，以及新图标不透明背景和未提供 monochrome 图层的提示；发布检查通过。
- **36 项设备测试已编译，未执行。** 当前没有连接 Android 设备或模拟器，未实际覆盖安装、操作相机或验证真机桌面显示。
- 最终合并 Manifest 保留原数据保护设置，没有新增联网、相机或全相册权限。

证据位于任务目录 `work/verification-v1.8.1/`：构建日志、JUnit、Lint、签名核对、资源定位、打包图标、保护文件哈希、旧文件哈希及产物元数据。

## GitHub 与群公告文案

`README.md` 使用用户原句“男大学生为了方便自己减脂自己做了这个饮食规划工具”。`GITHUB_TEXT_REVIEW.md` 汇总仓库简介、Release 文案和审核文件入口；`QQ_GROUP_ANNOUNCEMENT.md` 提供详细版与简短版公告。原技术说明保存在 `DEVELOPMENT.md`。

法律和发布文案均供用户审阅。开发者名称、联系邮箱、生效日期、源码许可证与公开网址仍待用户决定；没有代用户公开仓库或发送群消息，也没有在 App 中增加法律条款确认弹窗。APK 本次仅修改图标资源、Manifest 中的 roundIcon 和版本信息。
