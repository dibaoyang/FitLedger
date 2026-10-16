# 轻衡 1.5 构建与兼容验证

日期：2026-10-11。继续 D 盘既有工程，未修改 C 盘旧目录。

## 实际执行结果

assembleRelease testDebugUnitTest lintDebug assembleDebugAndroidTest --offline
BUILD SUCCESSFUL in 2m 2s
121 actionable tasks: 56 executed, 65 up-to-date

69 项单元测试全部通过，0 失败/错误：DomainTest 15、EnergyCalculatorsTest 15、ExerciseCalculatorTest 4、VisibilityMigrationTest 7、BackupMigrationTest 13、BirthdayParserTest 7、DeficitTargetTest 8。

覆盖生日四种输入格式、真实日期/闰日/未来日期/生日当天年龄、缺口与剩余脂肪、固定/体重/瘦体重/比例规则、周期/渐降预算、负数/无效输入、kJ、旧 JSON 默认规则、备份往返、旧迁移和历史数据保留。

Android Lint：0 错误，3 个非阻断提示（返回值 Composable 命名、Kapt/KSP、旧资源限定目录）。没有关闭错误检查。
19 项 Android 仪器测试编译成功，未在设备运行。新增今日预算立即生效保持过去目标、摄入、运动不变，以及尚未称重时修复待生成计划、已有快照时拒绝该入口的测试。手机覆盖安装和 UI 操作尚未实测。

构建使用 D 盘 Gradle 8.9、SDK 35/Build Tools 34.0.0、JDK 21（代码目标 Java 17）及本地依赖。沙箱阻止 Kotlin daemon 写入 C 盘临时标记，编译器自动回退为无 daemon 编译并成功；没有省略所需检查。

## 数据和安装身份

versionName 1.5，versionCode 6，applicationId com.fitledger，最低 API 26、目标 API 35。release APK 不含 debuggable 标记。

Room 仍为 version 4，无新增 Room Migration。Database.kt、Migrations.kt 以及 schemas 1/2/3/4 与已交付 1.4 源码包逐字节一致。数据库/设置名不变，无普通升级清库行为。预算新字段只保存在计划 JSON，旧计划默认关闭新功能；备份格式 6，兼容 1–6。

新 APK 通过 apksigner 校验，与 1.4 证书完全相同：
45bbe006cb3504fbb0e422289f4c04ab433f4e226d9985101968f5312e7b7af0

FitLedger-v1.5-update.apk SHA-256：
41db49e9719d4c1c4b4366ae2b0082e11f4ff6395e473f62d7ebdcb4f99c3afc

比较对象是此前交付的原 APK；没有直接读取手机当前安装证书。若手机安装的是此前交付原包，新包保持相同身份和签名且版本递增，可用于覆盖更新。先导出外部备份，直接打开新 APK 更新，不卸载、不清除存储。若系统提示签名冲突，请保留原 App 并反馈。

证据位于工程同级 verification-v1.5/，含构建日志、JUnit XML、Lint、签名、包信息和数据库文件一致性摘要。具体用法和文件清单见 UPDATE_1.5.md；旧记录保存在 BUILD_STATUS_1.4.md 等历史文件中。
