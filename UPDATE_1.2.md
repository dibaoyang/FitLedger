# 轻衡 1.2 更新说明

在原 FitLedger 工程上升级。applicationId 仍为 com.fitledger；数据库仍为 ledger.db，DataStore 仍为 settings。版本号 1.2，versionCode 3。

## 使用方式

1. 升级前在旧版设置中导出备份，另存电脑或外部存储。保留旧 App，直接安装 FitLedger-v1.2-update.apk，不能先卸载或清除存储。
2. 在设置中补全性别、身高、生日、活动等级并保存。年龄按记录日期自动计算；五种活动系数是 1.2、1.375、1.55、1.725、1.9，也可自定义。
3. 每日仍必须手动录入体重、体脂率和肌肉量，瘦体重自动计算。新记录同时保存当日资料、年龄、BMR、活动系数和估算消耗快照。
4. 顶部“隐藏身体数据”开关保存后重启仍有效。隐藏性别、身高、生日、年龄、体重、体脂率、瘦体重、肌肉量、BMR及对应趋势曲线；输入中的敏感数值也遮挡。估算消耗、饮食与营养目标仍显示。编辑基础资料前需关闭隐藏。此功能是屏幕显示保护，备份仍包含完整资料，未做文件加密。
5. 设置中选择“热量比例”计划，可直接设置目标摄入，也可使用估算消耗减缺口/加盈余。5:3:2 和 4.5:3.5:2 等非负比例均可；三项不能全零，拒绝非有限数。示例 2000 kcal、5:3:2 得到碳水 250g、蛋白质 150g、脂肪约 44.44g。
6. 正常保存计划从明天开始，资料保存也不会重算今天或历史。确认“立即生效”才会用今日已保存的身体测量值和本次资料、计划重算今日能量与营养目标；今日食品记录及过去日期不变。
7. 计划热量差是消耗减目标；估算实际热量差是消耗减已记录摄入。正数为缺口，负数为盈余。后者基于当前已记录摄入，不代表尚未记录完的全天最终结果。

全部能量内部以 kcal 保存与计算，kJ 输入/显示按 4.184 换算。现有 kcal/kJ 偏好、食品、推荐与锁定食品、原计划模式、7/30/90 天趋势和提醒均保留。

## 计算与历史规则

男性 BMR = 88.362 + 13.397×体重kg + 4.799×身高cm − 5.677×年龄。
女性 BMR = 447.593 + 9.247×体重kg + 3.098×身高cm − 4.330×年龄。
每日估算消耗 = BMR × 活动系数。年龄按生日和记录日期的周岁计算。
比例按总和归一化，热量分别除以 4、4、9 得到碳水、蛋白质、脂肪克数。

升级当天若已有旧版身体记录，原目标保持不变，新增能量栏显示缺失。可等待下一天产生完整快照，或补全资料后主动确认立即生效来更新今天。不会批量反向填充旧日期。

## 数据库及备份兼容

Room version 2 → 3，新增 MIGRATION_2_3，只给 bodies 表添加以下可空字段：

- energy_age INTEGER
- energy_heightCm REAL
- energy_sex TEXT
- energy_birthday INTEGER
- energy_activityFactor REAL
- energy_bmrKcal REAL
- energy_expenditureKcal REAL

旧行这些字段保持 NULL；原有身体、目标、食品、摄入与设置不被清空。保留 MIGRATION_1_2，支持 v1 → v2 → v3。1.json、2.json 与上一版源码包逐字节一致；新增 3.json。没有 destructive migration，也没有启动清库逻辑。

最新资料和隐私保存在新增 DataStore 键 body_profile/body_privacy；旧版缺省为未填写资料、关闭隐藏。计划 JSON 新字段有兼容默认值，不改变原计划含义。

备份版本为 3，带 databaseVersion、exportTime、appVersion，包含能量快照、资料、隐私和比例配置；支持读取版本 1/2/3。旧备份缺失能量仍为空。恢复仍先校验、明确确认、保存恢复前副本，再事务替换；通过持久设置日志处理数据库与 DataStore 同步中断。恢复与立即生效在同步完成前关闭数据界面，避免短暂显示尚未应用隐私设置的数据。

## 修改/新增文件

以下路径相对于工程目录：

| 文件 | 修改内容 |
|---|---|
| app/src/main/java/com/fitledger/domain/EnergyCalculators.kt（新增） | 年龄、BMR、活动消耗、资料、能量快照、比例计算 |
| app/src/main/java/com/fitledger/domain/Models.kt | 比例计划及目标计算扩展，保留旧模式 |
| app/src/main/java/com/fitledger/data/Database.kt | 可空每日能量快照、今日更新 DAO、数据库 v3 |
| app/src/main/java/com/fitledger/data/Migrations.kt | 注册非破坏性 2→3 迁移 |
| app/src/main/java/com/fitledger/data/ProfileCodec.kt（新增） | 资料、能量快照序列化 |
| app/src/main/java/com/fitledger/data/Settings.kt | 资料、隐私、比例字段与旧值默认兼容 |
| app/src/main/java/com/fitledger/data/Repository.kt | 称重时生成能量快照、今日事务重算 |
| app/src/main/java/com/fitledger/data/Backup.kt | v3 备份兼容与快照校验 |
| app/src/main/java/com/fitledger/ui/LedgerViewModel.kt | 资料与隐私操作、立即生效及恢复同步门禁 |
| app/src/main/java/com/fitledger/ui/Screens.kt | 隐私开关、称重预览、首页消耗和热量差 |
| app/src/main/java/com/fitledger/ui/ProfileRatioEditors.kt（新增） | 资料和比例计划编辑器 |
| app/src/main/java/com/fitledger/ui/SettingsTrends.kt | 计划确认、目标预览、新趋势指标和曲线隐藏 |
| app/src/test/java/com/fitledger/domain/EnergyCalculatorsTest.kt（新增） | 公式、年龄、比例、非法值、单位测试 |
| app/src/test/java/com/fitledger/data/BackupMigrationTest.kt | v3/旧备份、真实 SQLite 迁移数据保留测试 |
| app/src/androidTest/java/com/fitledger/data/RepositoryTest.kt | 今日重算隔离、资料及隐私不改快照测试 |
| app/src/androidTest/java/com/fitledger/data/MigrationTest.kt | Room 1→3、2→3 迁移测试 |
| app/schemas/com.fitledger.data.LedgerDatabase/3.json（新增） | Room v3 导出 schema |
| app/build.gradle.kts | versionCode 3、versionName 1.2，沿用原签名 |
| README.md、AGENTS.md、BUILD_STATUS.md、UPDATE_1.2.md | 使用、兼容契约和验证记录 |

## 安装与验证边界

新 APK 已通过签名验证，证书与 1.1 完全相同；applicationId 不变，版本号递增，满足覆盖更新的身份条件。升级逻辑不主动删除旧数据。若手机报告签名冲突，请停止安装，不要通过卸载绕过。

40 项宿主机测试通过，Lint 0 错误；16 项设备测试编译成功，但未连接真机或模拟器执行，也尚未验证手机上的实际覆盖安装、通知与系统文件选择器。详细结果及文件摘要见 BUILD_STATUS.md。请安装后核对历史记录并再导出一份备份。

