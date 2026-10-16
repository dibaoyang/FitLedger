# 轻衡 1.3：独立身体信息显示

本次继续修改现有 FitLedger 项目。versionName 1.3，versionCode 4；applicationId 仍为 com.fitledger。

## 新功能

性别、身高、生日、年龄、体重、体脂率、瘦体重、肌肉量、BMR 各有独立眼睛按钮。点击一项只改变这一项，界面上“全部隐藏/全部显示”是可选快捷操作。首页、身体输入、资料设置和趋势共用同一项设置。

隐藏趋势指标时不生成其曲线、范围或最近值。输入框隐藏真实字符；性别和生日隐藏时显示遮罩，要修改这两项请先点击其眼睛显示。其他项目仍可独立显示。今日估算热量消耗始终显示。

隐藏只影响界面，不修改身体测量、真实资料或计算；所有真实值仍参与瘦体重、BMR、消耗、目标等计算。未保存的资料编辑状态也不会因为点击眼睛被清空。隐藏是显示控制，导出备份仍包含完整真实数据。

## 已安装版本升级

Room 数据库仍为 version 3，本次没有新增 Room Migration；原 MIGRATION_1_2、MIGRATION_2_3 继续保留。数据库名 ledger.db、DataStore 名 settings 均不变。数据库结构与上一版完全一致，不重建数据库、不清空设置、不调用 destructive migration。

新增 DataStore VisibilityMigration：在旧 settings 文件首次读出之前，以事务填入九个缺失的布尔键 showGender、showHeight、showBirthday、showAge、showWeight、showBodyFat、showLeanBodyMass、showMuscleMass、showBmr。

- 旧 body_privacy=true：缺失项默认全部隐藏。
- 旧 body_privacy=false 或不存在：缺失项默认全部显示。
- 已存在的独立键原样保留，迁移可安全重试。
- 旧 body_privacy 和所有其他键保留，不删除旧 DataStore。
- 以后点击眼睛只写对应一个键；重启读取同一个持久文件。

备份格式由 3 升为 4，数据库版本仍为 3。新版可恢复 v1/v2/v3/v4 备份；旧备份和升级前未完成的设置同步日志根据原 privacy 标志获得独立默认值。新版备份包含完整九项状态，旧 App 不应读取新版备份。

## 主要文件

新增：
- app/src/main/java/com/fitledger/domain/BodyVisibility.kt：九个字段与独立显示模型。
- app/src/main/java/com/fitledger/data/VisibilityMigration.kt：DataStore 安全迁移与备份序列化。
- app/src/main/java/com/fitledger/ui/BodyPrivacy.kt：独立眼睛、遮罩与共享界面状态。
- app/src/test/java/com/fitledger/data/VisibilityMigrationTest.kt：旧文件迁移、重新打开、隔离及设置兼容测试。

修改：
- data/Settings.kt：注册迁移，独立持久化及恢复。
- data/Backup.kt：备份格式 v4，兼容 v1–v3。
- ui/LedgerViewModel.kt：单项及批量显示操作，仅调用设置存储。
- ui/Screens.kt：移除统一开关，首页与录入项分别控制。
- ui/ProfileRatioEditors.kt：性别、身高、生日、年龄独立显示。
- ui/SettingsTrends.kt：趋势按对应字段独立遮挡。
- app/src/androidTest/java/com/fitledger/data/RepositoryTest.kt：调整隐私持久化断言。
- app/build.gradle.kts：应用版本递增，签名配置不变。

上述简写 data/、ui/ 均位于 app/src/main/java/com/fitledger/。Database.kt、Migrations.kt、Repository.kt 与 1.2 保持一致；原身体、食品、摄入、目标写入逻辑未改。

## 覆盖安装

1. 在手机当前版本导出备份，另存到 App 之外。
2. 下载 FitLedger-v1.3-update.apk，直接打开并选择更新；不要卸载旧 App，不要清除应用存储。
3. 打开后核对历史记录。旧版隐藏状态会自动转为各项相同状态，再按需要单独调整。

打包时已核对工作区内此前交付的 APK，applicationId 和证书一致，版本号递增。未连接你的手机读取其实际安装证书；如果你安装的就是此前交付的原 APK，新包与它签名兼容。若系统提示签名不一致或无法更新，请保留原 App 并反馈，不要卸载绕过。

测试与签名证据见 BUILD_STATUS.md 和工程同级 verification-v1.3/。真机覆盖安装及界面交互尚未实测。
