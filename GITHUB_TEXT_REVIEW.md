# GitHub 公开文案审核稿

这份文件汇总准备放在 GitHub 上的文字，供你先阅读和修改。尚未代你上传或发布。

## About 简介

男大学生为了方便自己减脂自己做了这个饮食规划工具

## Release 标题

轻衡 FitLedger 1.8.1

## Release 正文

轻衡是我为了方便自己记录饮食、控制营养摄入而做的 Android 工具，无需注册账号，记录保存在本机。

1.8.1 更新了应用图标，将参考角色、猪肚鸡汤与哑铃元素结合。保留 1.8 的首次须知与独立首次设置、肌肉量免填、直接添加营养、食品代码分享，以及离线包装营养表识别。

- 最低 Android 8.0；versionName 1.8.1，versionCode 10。
- 安装文件：`FitLedger-v1.8.1-update.apk`。
- 源码文件：`FitLedger-v1.8.1-source.zip`。
- Room v6、备份格式 v9，本次无新增 Migration。

升级前请在旧 App 导出备份并另存，随后直接覆盖安装；不要卸载旧 App，不要清除应用数据。包名与签名核对、构建和设备验证范围请查看构建报告。

营养与能量结果为估算；OCR 只协助读包装上的营养表，保存前请人工核对。备份含身体数据，当前 JSON 文件未设置独立密码加密，请妥善保管，不要公开上传。

详情见 [项目说明](README.md)、[图标更新公告](UPDATE_1.8.1.md)、[1.8 功能公告](UPDATE_1.8.md)、[使用条款与隐私说明](TERMS_AND_PRIVACY_1.8.md)和[构建报告](BUILD_STATUS.md)。

## 你需要决定或补全

- 发布者／开发者名称：猪肚鸡（按发布者提供的信息填写）。
- 联系邮箱：2239254611@qq.com（按发布者提供的信息填写）。
- 生效日期：当前拟为 2026-10-17，正式发布时核对实际日期。
- 源码许可证：目前未选择，不会代你默认授权为 MIT 等开源许可证。

文字都可以按你的表达调整；功能、数据处理和兼容承诺应与实际版本一致。计划仓库网址为 https://github.com/dibaoyang/FitLedger ，正式发布前仍以本地预览为准。

## 全部审核文件

- [README.md](README.md)：GitHub 首页，面向下载和使用软件的人。
- [GITHUB_PUBLISHING.md](GITHUB_PUBLISHING.md)：上传哪些文件、Release 发布方式和发布前检查。
- [TERMS_AND_PRIVACY_1.8.md](TERMS_AND_PRIVACY_1.8.md)：已填写发布信息的使用条款与隐私审核稿。
- [UPDATE_1.8.1.md](UPDATE_1.8.1.md)：本次图标更新。
- [UPDATE_1.8.md](UPDATE_1.8.md)：首次设置、直接营养、食品代码、离线识别等功能更新。
- [BUILD_STATUS.md](BUILD_STATUS.md)：实际构建、签名核对与设备验证范围。
- [DEVELOPMENT.md](DEVELOPMENT.md)：保留的完整技术及历史功能说明。
- [QQ_GROUP_ANNOUNCEMENT.md](QQ_GROUP_ANNOUNCEMENT.md)：朋友下载交流群公告，提供详细版和简短版。

本次整理的是项目文档，**没有增加 App 内的法律条款确认、隐私同意弹窗或新的首次使用界面**；原有须知功能仍按应用实现运行。
