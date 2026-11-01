# FitLedger GitHub发布说明

本版1.11／versionCode14。先审阅[公开文案](GITHUB_TEXT_REVIEW.md)，经用户确认后更新现有仓库。

## 仓库信息

- 继续使用公开仓库：[dibaoyang/FitLedger](https://github.com/dibaoyang/FitLedger)。
- About：男大学生为了方便自己减脂自己做了这个饮食规划工具。
- Topics：android、kotlin、jetpack-compose、nutrition-tracker、fitness、offline-first、room-database。
- 发布者：猪肚鸡；反馈邮箱：2239254611@qq.com。

## 更新步骤

1. 同步远程main，保留提交历史及已有Release。
2. 把本文件夹里的源码、图标、构建文件和说明更新到仓库根目录，不多套一层FitLedger。
3. 新建v1.11标签，标题「轻衡 FitLedger 1.11」，使用已确认的更新说明，包含1.10及以前功能摘要。
4. Release附上FitLedger-v1.11-update.apk和FitLedger-v1.11-source.zip；安装包不提交到源码历史，旧版发布及附件保留。
5. 核对公开首页、说明链接、下载状态以及附件大小和SHA-256。

## 兼容与文件

- 版本1.11／14，包名com.fitledger，原更新签名；Room13完整迁移1→13，备份17可读取1～16。
- 升级前导出JSON备份并另存，直接覆盖安装；新版备份不能导回旧App。
- 发布者、邮箱和条款原生效日期沿用此前资料；本次修订日期2026-11-01。
- 许可证尚未选择，不默认添加MIT等授权；第三方依赖适用其许可证。
- 不上传签名私钥、真实数据库／备份、SDK、local.properties、安装包或构建缓存。
- 本地检查与设备测试编译不能代替手机实测，验证范围见[构建记录](BUILD_STATUS.md)。

[项目说明](README.md) · [完整更新公告](UPDATE_1.11.md) · [此前1.10功能](UPDATE_1.10.md) · [使用条款与隐私说明](TERMS_AND_PRIVACY_1.8.md)
