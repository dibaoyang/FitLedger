# 轻衡 1.1 构建与兼容性验证

日期：2026-09-18。此次继续修改原 FitLedger 工程，没有创建替代应用。

## 实际执行结果

```text
assembleRelease testDebugUnitTest lintDebug assembleDebugAndroidTest
BUILD SUCCESSFUL in 1m 18s
121 actionable tasks: 35 executed, 86 up-to-date
```

- 单元测试：22 项全部通过（DomainTest 15 项、BackupMigrationTest 7 项），0 失败/错误。
- 包含实际 SQLite 数据库测试：按导出的 v1 schema 建表并写入四类旧数据，调用实际 MIGRATION_1_2 SQL，然后逐字段核对旧记录、默认 kcal 单位、新版表结构与 SQLite integrity_check。
- 备份测试覆盖完整往返、旧格式默认值、损坏校验和、未来版本、重复编号、关联缺失与次日计划。
- 旧版 1.json 与上一版源码 ZIP 中的 1.json 逐字节一致，SHA-256：07c60a5bd97b1c5df54433e345db35beffb1b8b42bdf86364697f2eb383c6647。
- Android Lint：0 错误，2 个非阻断提示（Kapt 可换为 KSP、旧资源限定目录可合并）。没有关闭错误检查或建立忽略基线。
- Android 仪器测试共 13 项，源码与测试 APK 编译成功；没有在真机/模拟器上执行，不能标记为通过。包括 Room MigrationTestHelper、立即生效历史隔离、恢复回滚、DataStore 次日计划及设置同步中断重试。

## 更新包身份与签名

- 文件：FitLedger-v1.1-update.apk，release 构建，非 debuggable。
- applicationId：com.fitledger（不变）。
- versionCode：2；versionName：1.1。
- Android 最低 API 26，目标 API 35。
- 新旧 APK 均通过 apksigner 校验，证书 SHA-256 完全一致：
  45bbe006cb3504fbb0e422289f4c04ab433f4e226d9985101968f5312e7b7af0
- 新 APK 文件 SHA-256：
  bbe779b8fd7f680af9ec0fcbc2c360a01fb0d3c357923419220057e26ff54095

构建脚本会验证原密钥存在且证书指纹正确，不允许悄悄生成不兼容的新签名。私钥单独保存在工程外，源码包不包含私钥。

## 数据兼容

数据库版本由 1 升至 2，注册 MIGRATION_1_2：只给 foods 增加 energyInputUnit 默认 KCAL，并新增 pending_settings 日志表。原四张业务表的已有行保持不变。DataStore 文件名、既有键、数据库名均不变。未使用破坏性迁移。

立即生效仅更新今天目标；备份恢复的覆盖操作有用户确认，执行前保留副本，数据库替换在单一事务中完成。数据库与设置跨存储同步采用持久日志，中断后先恢复同步再开放操作。详细设计、SQL 和文件清单见 UPDATE_1.1.md。

## 验证范围与安装方法

未完成手机上的真实 1.0→1.1 覆盖安装、系统文件选择器交互、通知行为与后台杀进程恢复验收。宿主机 SQLite 测试不等同于 Android 设备上的 Room 测试。

请保留旧 App，直接覆盖安装新版，不要卸载或清除存储。1.0 没有导出功能，本次升级后先检查历史记录并导出外部备份；以后每次升级前先备份。如果系统提示签名冲突，停止安装并反馈，不要通过卸载绕过。

工程同级 verification-v1.1/ 保存最终构建日志、JUnit XML、Lint 报告、签名和包信息。上一版构建记录保存在 BUILD_STATUS_1.0.md。
