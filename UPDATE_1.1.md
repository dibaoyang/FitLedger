# 轻衡 1.1 更新说明

本次直接修改原工程，`applicationId` 仍为 `com.fitledger`，数据库文件仍为 `ledger.db`，DataStore 仍为 `settings`。`versionCode` 从 1 增至 2，`versionName` 为 1.1。

## 你应该怎样安装

1. **保留手机上的旧 App，不要卸载，也不要清除存储/数据。** 下载 `FitLedger-v1.1-update.apk`，直接打开并选择系统的更新/安装。
2. 本次更新包使用与 1.0 完全相同的签名证书。系统应将它识别为同一个 App 的更新；不是一个新的独立 App。
3. 更新后先检查食品库、身体历史、历史摄入和计划，再到「设置 → 数据管理 → 导出备份」，另外保存到电脑或网盘。当天尚未称重时，可展开称重页的「计划、热量单位与数据备份恢复」，不需要先录入身体数据才能备份。
4. 以后更新前先导出备份，再覆盖安装同签名、较高版本号的 APK。若系统报告签名冲突，**停止安装并反馈，不要用卸载来解决**。
5. 卸载后重装、清除应用数据、换手机都不等于覆盖升级。需要先在旧设备导出备份，再在新安装中恢复。

Android 对更新的签名要求见[官方签名说明](https://developer.android.com/studio/publish/app-signing)。本次能校验包名、版本号、签名和迁移逻辑，但不能替代你手机上的真实覆盖安装验收，也不能承诺对设备故障或系统清除数据免疫。

## 热量单位

- 新增/编辑食品支持 kcal 和 kJ，`1 kcal = 4.184 kJ`。切换食品输入单位时自动换算；仅切换单位不反复舍入保存值。
- 新增「默认热量单位」设置，首页、食品列表、摄入、推荐、目标预览、计划热量输入和热量趋势都会使用所选单位。
- 食品编辑器默认采用当前显示偏好，可自行切换；保存最后使用的输入单位。旧食品的输入单位默认为 KCAL。
- 数据库、饮食快照、目标快照、计划规则及推荐算法中的数值统一为 kcal。历史 kcal 数值不批量转换，也不会改变。

## 计划生效规则

- **正常保存**：明天起使用新计划，即使今天尚未称重也不会提前采用。已生成的今日及历史 DailyTarget 不变。
- **立即生效**：必须已经完成当天身体记录与目标生成；按钮先弹出确认对话框。确认后用当天已保存的体重、体脂、瘦体重快照及肌肉量计算，仅 UPDATE 当天目标，同时把新计划设为后续使用的计划。
- 历史目标、所有身体记录和全部摄入记录均不修改。剩余营养由新目标减已有摄入自动显示。确认过程中跨午夜时拒绝执行，需重新确认。
- 固定宏量计划仍按三大营养素换算目标热量。若希望严格指定例如 2000 kcal，需配置一个宏量采用剩余热量，而不是只改变固定宏量之外的热量基准。

## 数据库升级：1 → 2

新增并注册 `Migrations.MIGRATION_1_2`，保留导出的 `1.json`，新增 `2.json`。

```sql
ALTER TABLE foods ADD COLUMN energyInputUnit TEXT NOT NULL DEFAULT 'KCAL';
CREATE TABLE IF NOT EXISTS pending_settings (
    id INTEGER NOT NULL,
    payload TEXT NOT NULL,
    PRIMARY KEY(id)
);
```

第一条语句只增加带默认值的列。第二张表保存跨 Room/DataStore 操作的待完成设置。四张旧业务表不重建，不清空，不 UPDATE 旧历史行。

没有启用 `fallbackToDestructiveMigration`；启动、升级和初始化都不会清除业务数据。所有批量删除仅位于用户确认后的备份恢复事务。Room 版本与 App 版本分别管理；以后结构变化必须追加 Migration，并保留旧迁移链，见工程 `AGENTS.md`。依据：[Room 官方迁移文档](https://developer.android.com/training/data-storage/room/migrating-db-versions)。

DataStore 保留原有文件和键，新增单位偏好与待生效计划键。缺少热量单位的旧设置默认为 KCAL，旧 plan 继续有效；普通升级不清除任何设置。

## 备份与恢复

- 系统文件选择器导出 JSON；包含身体、食品、饮食、目标快照、完整计划、待生效计划、提醒和单位偏好。
- 顶层包含 `backupVersion`、`databaseVersion`、`exportTime`、`appVersion`；当前格式 backupVersion=2，databaseVersion=2。内容带 SHA-256 校验，用于识别意外损坏，不是加密或身份验证。
- 支持已定义的 v1 kcal-only JSON 格式，缺少食品输入单位和默认单位时补 KCAL；1.0 App 本身没有备份功能，因此不能声称它曾导出过这种文件。不支持直接导入任意 SQLite 文件。来自更高 backupVersion/databaseVersion 的文件会被拒绝，提示升级 App。
- 恢复前完整解析并检查版本、校验和、数值、重复编号及记录关联；预览时间、来源版本及数量，再要求确认整体覆盖。损坏或不兼容文件在修改数据前被拒绝。
- 恢复采用整份替换，不合并。先在 App 内原子保存恢复前副本；保存失败就停止恢复。可通过「导出上次恢复前副本」将其另存后再次恢复。仅保留最近一次副本，且卸载会删除这个内部副本，因此它不能替代外部备份。
- 四张业务表和待恢复设置在同一 Room 事务中提交。之后写入 DataStore、重新安排提醒，最后清除待完成日志。若进程在两阶段之间退出，下一次启动先完成日志，再开放记录操作。不会出现恢复一半后允许继续写入的情况。
- 当前支持最大 20 MB、单类 100000 条记录；超限拒绝，不截断。提醒偏好可恢复，Android 的通知授权不能由备份自动授予。
- 系统自动云备份仍关闭。手动导出的文件不加密，请妥善保存。若数据库文件本身损坏到无法打开，本版不自动删除它来强行恢复；应保留原文件并反馈，避免不可逆地丢失数据。

## 签名保管

原密钥已另存到工程目录外的 `../private-signing/fitledger-update.keystore`，未放进源码压缩包。它来自最初 1.0 的 Android 调试密钥；为保留已安装数据，本次 release 构建继续使用该历史密钥，**不是换了一把新的发行密钥**。

固定证书 SHA-256：

```text
45bbe006cb3504fbb0e422289f4c04ab433f4e226d9985101968f5312e7b7af0
```

构建时检查私钥文件存在并检查上述证书指纹；缺失或不匹配就拒绝签名。可以用 `-PfitledgerKeystore=绝对路径` 指定该原密钥的备份位置。请把私钥单独保存在安全位置，不要上传公共仓库或与 APK 一起分享。只有源码没有私钥，不能生成兼容覆盖安装的更新包。

## 主要文件变化

| 文件 | 修改 |
|---|---|
| `domain/EnergyUnit.kt` | 新增 kcal/kJ 转换 |
| `domain/Models.kt` | 目标计算支持明确传入已保存瘦体重 |
| `data/Database.kt` | v2 实体、食品单位、设置日志、目标更新与恢复 DAO |
| `data/Migrations.kt` | 显式 1→2 迁移及集中注册列表 |
| `data/Settings.kt` | 默认单位、次日计划、兼容旧设置、设置序列化 |
| `data/Backup.kt` | 版本化备份、校验和、兼容读取及完整验证 |
| `data/Repository.kt` | 今日目标更新、事务备份/恢复、待完成设置处理 |
| `FitApp.kt` | 注册迁移，串行化数据操作，启动恢复未完成设置 |
| `ui/LedgerViewModel.kt` | 单位、立即生效、文件导入导出、恢复前副本与中断恢复 |
| `ui/Screens.kt` | 食品输入单位切换、统一热量显示、数据同步门禁 |
| `ui/SettingsTrends.kt` | 设置/趋势单位、两个生效按钮、确认窗口、数据管理 |
| `work/Reminders.kt` | 后台任务使用前完成待恢复设置 |
| `app/build.gradle.kts` | 版本提升、固定原签名并校验、测试依赖、schema 测试资源 |
| `app/schemas/.../2.json` | 新版 schema；1.json 保留 |
| `DomainTest.kt` / `BackupMigrationTest.kt` | 单位、快照计算、备份与实际 SQLite 迁移测试 |
| `RepositoryTest.kt` / `MigrationTest.kt` | 即时目标历史隔离、恢复与 Room 迁移仪器测试 |
| `AGENTS.md` | 后续开发必须遵循的数据及签名兼容约定 |

最终执行结果见 `BUILD_STATUS.md`；不要把“测试源码编译成功”理解为“已完成真机升级验收”。
