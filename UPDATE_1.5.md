# 轻衡 1.5：生日识别与按热量缺口分配宏量

继续在 D:\Codex\Workspaces\2026-09-17\referenced-chatgpt-conversation-this-is-an 的现有工程升级。applicationId 仍为 com.fitledger，versionName 1.5，versionCode 6。

## 生日

设置中的生日可输入 2000年1月1日、2000.1.1、2000-1-1、2000-01-01；四种写法均识别为同一天，显示规范中文日期。年龄按当前日期实时计算周岁，生日当天更新；不存在的日期、未来生日和超出原有年龄范围的日期不会保存。

存储仍使用原 epoch day 字段。生日和年龄的独立显示/隐藏继续生效。修改生日时设置页年龄立即更新；正常保存资料不反向改写历史。若今日已生成能量和身体资料快照，需要确认“立即生效”才更新今日快照中的年龄、BMR及营养目标。

## 设置缺口并自动计算脂肪

1. 打开设置，选择固定宏量或身体数据计算计划（碳循环、碳水渐降同样支持）。
2. “热量目标方式”选择“当天估算消耗 − 热量缺口”。
3. 输入缺口，例如 500 kcal；使用 kJ 显示时对应为 2092 kJ，内部统一 kcal。0 表示维持。
4. 设置碳水和蛋白质。支持固定克数、每 kg 体重、每 kg 瘦体重和热量百分比。
5. 点击“脂肪使用剩余热量”，或在脂肪计算方式选择“剩余热量”。
6. 查看预览，再正常保存或确认立即生效。

例：当天估算消耗 2500 kcal − 缺口 500 kcal = 目标 2000 kcal。
固定碳水 250g、蛋白质 150g，脂肪 = (2000 − 250×4 − 150×4) / 9 ≈ 44.44g。

本版沿用当前计划口径：估算消耗来自当天身体资料、BMR 和活动系数，不额外累加运动记录。运动仍参与首页“实际热量差”计算，不会自动改动已保存的饮食目标。

预算模式须且只能让一种宏量使用剩余热量；也可让碳水或蛋白质承接剩余。已定宏量超过预算、缺口大于等于估算消耗、负数/非有限缺口都会提示错误，不生成负脂肪。

碳循环/渐降配合新预算时，先调整当天碳水，再计算剩余宏量，使总热量符合缺口目标；此组合的碳水不能设为剩余。已有旧计划默认不开启新预算，仍保留原先“调整碳水后总热量随之变化”的行为。

## 生效与快照

正常保存仍从明天开始，不改今天或过去快照；确认立即生效只更新今天能量和目标，不改变身体测量值、饮食或运动记录。

如果今天尚未称重且当前规则无法生成目标，可在设置中调整规则并确认“用于今日首次称重”。它只修改今日尚未生成的规则，保留历史和已安排的未来计划；之后仍须输入体重、体脂率、肌肉量完成称重门禁。今天已有快照时该入口不可用。若未安排后续计划，选定规则继续用于后续日期，直到再次修改。

预览基于今日身体资料与星期；明日/后续目标使用当日实际身体记录计算，可能不同。无法满足的计划需手动修正，不静默降低固定宏量。

## 数据与备份

Room 仍为 v4，数据库 Schema、表结构、数据库名 ledger.db 和 DataStore 名 settings 不变；本次不新增 Room Migration，原 1→2→3→4 全链保留。新增 useExpenditureBudget、deficitKcal 仅在计划 JSON 中保存；旧 JSON 默认 useExpenditureBudget=false。

备份格式由 5 升至 6，支持读取 1–6，避免旧 App 错误解释新预算规则。身体历史、能量、食品、摄入、DailyTarget、运动、独立隐私、提醒、热量单位及其他既有设置继续保留。

## 主要文件

新增：
- app/src/main/java/com/fitledger/domain/BirthdayParser.kt
- app/src/main/java/com/fitledger/ui/DeficitEditor.kt
- app/src/test/java/com/fitledger/domain/BirthdayParserTest.kt
- app/src/test/java/com/fitledger/domain/DeficitTargetTest.kt

修改：
- domain/Models.kt：预算及剩余宏量计算，保留旧计划行为。
- data/Settings.kt、Backup.kt：计划 JSON 与备份版本兼容。
- data/Repository.kt、ui/LedgerViewModel.kt：今日首次生成前的计划纠正入口，通过现有持久日志同步。
- ui/ProfileRatioEditors.kt：生日自动识别与年龄预览。
- ui/SettingsTrends.kt：热量缺口、预览、错误提示和首次称重入口。
- app/src/test/java/com/fitledger/data/BackupMigrationTest.kt、app/src/androidTest/java/com/fitledger/data/RepositoryTest.kt：兼容与历史隔离测试。
- app/build.gradle.kts、README.md、BUILD_STATUS.md 和本文件。

简写的 domain/data/ui 路径均位于 app/src/main/java/com/fitledger。

## 安装

先在旧版导出外部备份，然后直接安装 FitLedger-v1.5-update.apk，选择更新。不要卸载旧 App，不要清除应用存储。证书已与此前交付 APK 核对；若手机系统提示签名冲突，请保留原 App 并反馈，不要卸载绕过。

详尽测试、签名和设备验证范围见 BUILD_STATUS.md。
