# 轻衡 1.7 发布构建

日期：2026-10-14。用户授权实现全部 37 类计划组合，与此前尚未发布的食品删除一起打包；打包前已提供范围表，完整编号表见 UPDATE_1.7.md。

## 产物

- APK：`FitLedger-v1.7-update.apk`，release、非 debuggable，7,558,680 字节。
- versionName：1.7；versionCode：8。
- applicationId：`com.fitledger`；最低 API 26，目标 API 35。
- APK SHA-256：`eca6836ff2648c5d2720c33e253832448992bc42c52e5f4e400e95830eb28568`。
- 源码归档：`FitLedger-v1.7-source.zip`；不含签名私钥、用户备份、构建缓存和安装包。
- 原有交付 APK 和源码归档保留，原 1.6 构建报告存为 BUILD_STATUS_1.6.md。

## 构建与检查

最终执行 `testDebugUnitTest lintDebug compileDebugAndroidTestKotlin assembleRelease assembleDebugAndroidTest --offline`，使用 Kotlin in-process 编译和原签名配置。

`BUILD SUCCESSFUL in 2m 31s`；121 项任务，28 执行，93 已为最新。

147 项主机自动化测试全部通过，0 失败、0 错误。包括旧计划兼容、各类预算、共享剩余、比例、约束冲突与有解边界、碳循环/渐降顺序、日期模板、七日参考、周基准、运动定向与限额、快照冻结、旧版备份及新版完整往返、损坏备份拒绝、DataStore 重开保留设置、食品删除与真实 SQLite 数据迁移。

独立代码复核通过。定向运动回补的回归检查明确验证：只增加指定宏量，其他宏量逐值不变；指定项超过上限时拒绝操作，不重新分配其他项目。

Android Lint：0 错误、3 个原有警告、1 个状态装箱优化提示；发布 Lint 通过。设备测试共 30 项已编译并生成测试 APK，含 4 项高级计划事务测试；**没有连接设备，未执行设备测试、实际手机界面操作或覆盖安装。**

测试日志、JUnit XML、Lint 结果、包元信息、证书核对和旧 Schema 比较位于任务目录 `work/verification-v1.7/`。

## 数据兼容

相对已发布 1.6，Room 4→5。唯一新增结构是 `foods.isDeleted INTEGER NOT NULL DEFAULT 0`，来自此前待发布的食品删除功能。`MIGRATION_4_5` 不删除数据；完整 1→2→3→4→5 链注册。Schema 1～4 已与 1.6 源码归档逐字节核对，完全相同。

高级计划使用已有计划 JSON 和 `DailyTarget.planSnapshot`，未再修改 Room。旧 JSON 缺少 `advanced` 时继续原计算路径，不自动转换。新目标附带版本化计算上下文，保存平均参考、周消耗基准、当时计入的运动等；运动重算复用这些快照。

备份默认版本 8，接受版本 1～7；高级计划不能伪装成旧格式保存。恢复前验证完整配置与目标计算依据，再执行原事务和设置恢复日志。BodyRecord、DailyTarget、食品、饮食、运动和全部重要设置均包含在备份中。

数据库 `ledger.db` 与 DataStore `settings` 名称不变；没有 destructive migration、启动清空或普通升级重置偏好。正常保存从明天生效；立即生效更改今天的目标/能量及以后计划；仅更新今天不写设置。这些操作都不改身体测量、已记录饮食或历史日期。

## 签名与安装

交付前使用 `apksigner verify --verbose --print-certs` 核对新包与输出目录中此前各份交付 APK；证书 SHA-256 为：

`45bbe006cb3504fbb0e422289f4c04ab433f4e226d9985101968f5312e7b7af0`

使用工程外原签名私钥，未生成新私钥。核对对象是交付文件；未直接读取用户手机上的当前安装证书。若当前安装的就是此前这些交付 APK，可以按同包名同签名更新。

先在旧 App 导出备份并另存，再打开新版 APK 选择“更新”，直接覆盖安装。**不要卸载旧 App，不要清除 App 存储。**若系统提示签名冲突或降级限制，保留旧版本并反馈，不使用卸载绕过。

## 主要修改文件

- `domain/AdvancedPlan.kt`、`domain/Models.kt`：可选高级模型、预算/分配/约束/日程/参考/运动计算，保留旧规则。
- `data/AdvancedPlanCodec.kt`、`data/Settings.kt`、`data/TargetPlanning.kt`、`data/Backup.kt`：版本化配置、计算上下文、旧数据兼容与备份校验。
- `data/Repository.kt`、`ui/LedgerViewModel.kt`：今日单独更新、运动与目标原子更新、称重时训练/休息选择。
- `ui/AdvancedPlanEditor.kt`、`ui/SettingsTrends.kt`、`ui/Screens.kt`、`ui/ExerciseSection.kt`：完整配置入口、预览、确认流程、统一消耗来源与展示。
- `data/Database.kt`、`data/Migrations.kt`、Schema `5.json` 及食品 UI：合并此前未发布的食品删除。
- 新增高级计算/编解码/快照集成/设备事务测试，保留并运行原有测试。
- `app/build.gradle.kts`、`README.md`、`AGENTS.md`、`UPDATE_1.7.md`：版本、发布约定、功能表及升级说明。
