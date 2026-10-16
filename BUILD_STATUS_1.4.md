# 轻衡 1.4 构建与升级验证

日期：2026-10-10。开发与交付位于 D:\Codex\Workspaces\2026-09-17\referenced-chatgpt-conversation-this-is-an。继续原工程，未新建替代应用，未改动 C 盘旧目录。

## 执行结果

assembleRelease testDebugUnitTest lintDebug assembleDebugAndroidTest
BUILD SUCCESSFUL in 1m 33s
121 actionable tasks: 40 executed, 81 up-to-date

53 项宿主机测试通过，0 失败/错误：DomainTest 15、EnergyCalculatorsTest 15、VisibilityMigrationTest 7、BackupMigrationTest 12、ExerciseCalculatorTest 4。

新增测试覆盖三种预设净消耗、时长非法值、缺口/盈余、缺失基础消耗、kJ 换算、运动备份往返与旧备份默认空记录，以及真实 SQLite v3→v4 迁移。迁移测试在旧 schema 中写入身体及能量快照、食品、摄入、DailyTarget、待同步设置，执行实际 Migration，再比较每个旧表的全部行和新 schema 列定义，确认原值不变。

Android Lint：0 错误，14 个提示（11 项依赖新版本提示，另有返回值 Composable 命名、Kapt/KSP、资源目录提示）。没有关闭 Lint 检查。
17 项设备测试已编译：包括 Room MigrationTestHelper 旧版本升级，以及运动记录门禁、记录运动不修改身体/目标/摄入、立即生效不改变运动、运动备份恢复和删除。没有在真机或模拟器执行，不能视为设备测试通过。

旧构建工具目录已不存在，本次在 D 盘重建 Gradle 8.9、SDK 35 / Build Tools 34.0.0 环境，使用已有 D 盘 JDK 21（代码目标仍为 Java 17）。首次依赖下载曾遭遇连接失败，修复通道后完成；个别依赖从官方 Maven 下载并核对其官方摘要，未改变项目依赖版本。

## 数据兼容

Room version 3→4。MIGRATION_3_4 只新增 exercises 表及 index_exercises_day 索引，不删除、不更新任何旧表。MIGRATION_1_2、MIGRATION_2_3 保留。旧 schemas 1/2/3 与 1.3 源码包逐字节一致，证据见 verification-v1.4/old-schemas.txt。

运动记录存储 kcal、当日采用体重、时长、MET、运动类型和是否计入额外消耗。手填消耗不伪造体重、时长或 MET。旧日期不补写运动记录。隐藏状态仍只影响 UI；主页隐藏行移至内容底部，数值保持遮挡。

备份格式 5 支持版本 1–5，新旧备份均校验后恢复。原有数据库名 ledger.db、DataStore settings、独立隐私设置和持久同步日志机制保留。没有 destructive migration 或普通升级清空数据的逻辑。

## APK 身份和签名

文件 FitLedger-v1.4-update.apk，release、非 debuggable。
applicationId com.fitledger；versionName 1.4；versionCode 5。
最低 API 26，目标 API 35。

apksigner 校验通过，证书与之前交付的 v1.3 完全一致：
45bbe006cb3504fbb0e422289f4c04ab433f4e226d9985101968f5312e7b7af0

APK SHA-256：
99be67b663e78705623ed59891d710277e1ead22f3478a4671bdc87bb7f7ae02

原签名私钥继续保存在工程外，不包含在源码包。上述证书比较基于此前交付原 APK，未读取用户手机当前安装包。

## 安装与验证边界

升级前先导出外部备份；直接打开新 APK 选择更新，不卸载、不清除存储。若手机使用此前交付的原 APK，新包身份/签名一致且版本递增，可用于覆盖更新。若系统提示签名冲突，请保留原 App 并反馈。

尚未实测手机上的覆盖安装、运动录入交互、隐藏行移动、系统文件选择器和通知。安装后请核对历史记录。证据保存在工程同级 verification-v1.4/；具体文件清单与计算参考见 UPDATE_1.4.md。
