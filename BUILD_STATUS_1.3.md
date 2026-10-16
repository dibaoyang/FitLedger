# 轻衡 1.3 构建与升级验证

日期：2026-09-20。原工程继续开发，非新建替代项目。

## 构建与测试

assembleRelease testDebugUnitTest lintDebug assembleDebugAndroidTest
BUILD SUCCESSFUL in 1m 6s
121 actionable tasks: 56 executed, 65 up-to-date

47 项单元测试全部通过，0 失败/错误：DomainTest 15、EnergyCalculatorsTest 15、BackupMigrationTest 10、VisibilityMigrationTest 7。

本次新增验证并非只检查新安装：创建真实旧版 settings.preferences_pb 文件，写入旧隐藏状态及计划/kJ 设置，关闭后以新迁移重新打开，核对所有默认隐藏，再单独显示年龄，关闭/重新打开确认状态和其他设置保留。另覆盖旧版显示默认值、部分迁移保留已有项、迁移幂等、九项逐一隔离、旧备份/同步日志设置解析和真实资料计算不变。既有 SQLite 数据迁移及备份测试也通过。

Android Lint 0 错误、3 个非阻断提示（Composable 返回值命名、Kapt/KSP、资源限定目录）。16 项 Android 仪器测试编译成功，但没有真机/模拟器，未执行；不能宣称手机覆盖安装及界面交互已实测。

## 数据安全检查

Room 仍为 version 3，本次没有新增 Room Migration。schema 1/2/3、Database.kt、Migrations.kt、Repository.kt 与已交付 1.2 源码包逐字节一致，摘要记录见 verification-v1.3/database-unchanged.txt。

新增的是 DataStore VisibilityMigration：事务补齐独立显示键，不删旧 body_privacy 或其他设置。旧隐藏映射为全部隐藏，旧显示映射为全部显示，已有独立项不被覆盖。独立显示写操作不访问数据库，不修改资料或计算输入。

不存在 destructive migration、启动清库或清空 DataStore。备份 version 4，支持旧 version 1/2/3，保留九项状态和全部真实数据。

## APK 身份

文件：FitLedger-v1.3-update.apk；release 构建，无 debuggable 标记。
applicationId：com.fitledger；versionName：1.3；versionCode：4。
最低 API 26，目标 API 35。

apksigner 验证通过。与工作区此前交付的 1.0、1.1、1.2 三个 APK 逐一比较，证书相同：
SHA-256 45bbe006cb3504fbb0e422289f4c04ab433f4e226d9985101968f5312e7b7af0

新 APK SHA-256：
1ae98febeda2b6140ea9a3888514df280f210645f2da303da76f2d4754574bcf

仍使用原个人项目密钥，未换签名，源码包不包含私钥。以上确认针对此前交付的原 APK；未直接读取手机当前安装包证书。

## 安装

如果手机安装的是此前交付的原 APK，新包具有相同 applicationId/签名及更高 versionCode，可作为覆盖更新包。升级前导出外部备份，直接打开新版选择更新，不要卸载、不要清除应用存储。遇到系统签名冲突请停止并反馈，不要卸载绕过。升级后核对历史记录及独立显示状态。

本轮完整证据位于工程同级 verification-v1.3/；文件清单与用法见 UPDATE_1.3.md。之前验证记录保留于 BUILD_STATUS_1.0.md、BUILD_STATUS_1.1.md、BUILD_STATUS_1.2.md。
