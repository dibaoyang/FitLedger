# 轻衡 1.8 发布构建

日期：2026-10-16。此版本在既有 `FitLedger` 工程中完成，包含此前待发布的首次须知、首次设置、可省略的肌肉量、直接营养记录、食品代码分享，以及本次新增的离线包装营养表识别。

## 版本与产物

- versionName：`1.8`；versionCode：`9`。
- applicationId：`com.fitledger`；最低 Android 8.0（API 26），目标 API 35。
- Room 数据库：v6；备份格式：v9。
- 发布 APK：`FitLedger-v1.8-update.apk`，52,325,758 字节（约 49.90 MiB），SHA-256：`14ae2c8f6145a0b2ef10d5015bde2a02dbe9b4cc18bb1a793be4e2ef66bb289b`。
- 源码归档：`FitLedger-v1.8-source.zip`，不包含签名私钥、用户备份、构建缓存、APK 或 AAB。最终文件哈希见 `work/verification-v1.8/artifacts.json`。

## 本次功能

- 首次启动先展示须知；确认阅读后才进入正常流程，已读状态在之后重启、更新和恢复旧设置时保留。
- 新用户在须知后进入独立的「首次设置」，保存性别、身高、生日和活动等级；已有资料的升级用户不重复填写。每天仍先录入当天身体数据。
- 肌肉量默认继续要求输入；每日页可「隐藏并免填」，提示显示两秒。开启后新记录以空值表示未记录，不把数据改成 0、不改历史数据、不停止既有计算。设置可恢复要求输入。
- 今日页可直接添加碳水、蛋白质、脂肪。热量统一以 `4×碳水 + 4×蛋白质 + 9×脂肪` kcal 计算；记录参与总摄入、剩余、热量差、趋势、备份和独立删除。
- 食品可生成/填写 `FITFOOD1` 分享代码。代码只含单项食品资料；导入总是新增，不覆盖原食品、历史摄入或目标。
- 食品编辑页可调用系统相机或系统照片选择器，使用内置中文文字识别模型读取包装营养表。识别只填写草稿，必须由用户核对并确认保存。多列、缺失、NRV%、每 100mL 等不可靠情况不会猜测数值。

## 数据与升级兼容

Room 从 v5 升至 v6，注册 `MIGRATION_5_6`，并保留完整 `1→2→3→4→5→6` 链。迁移在一个 Room 升级事务中逐列复制：

- `bodies.muscle` 从必填改为可空，已有肌肉量和全部能量快照原样复制；
- `intakes.foodId` 改为可空，并增加来源字段，所有旧摄入保持 FOOD 来源、原 ID、食品名、营养和自增编号；
- 旧的 `sqlite_sequence` 高水位同步保留，避免新记录重复旧编号。

实际 SQLite 迁移测试用 v1～v5 的身体、能量、食品、目标、摄入、删除编号和设置日志进行升级，逐字段比较历史值、表结构、索引和完整性检查。v1～v5 的已发布 schema 与 1.7 源码归档逐字节核对，均未改变。

`ledger.db`、DataStore `settings` 和 applicationId 未变。没有 destructive migration、启动清库、升级清除 DataStore 或历史重算逻辑。备份 v9 读取 v1～v8；新增的「未记录肌肉量」和「直接营养」不会伪装为可由旧备份格式安全表达的数据。

食品 OCR 不改 Room schema，因此数据库版本没有因该功能继续增加。图片不放入数据库或备份；相机临时文件在识别结束/取消后删除。

## 隐私与网络

最终 APK 的合并 Manifest 不包含 `INTERNET`、`ACCESS_NETWORK_STATE`、`CAMERA`、`READ_MEDIA_IMAGES` 或旧版外部存储读取权限。拍照由系统相机处理，选图使用系统选择器；应用不需全相册权限。移除了 ML Kit 带入的遥测传输后端，OCR 模型内置在 APK，识别不需下载模型或上传图片。

APK 内已检查到 25 个内置 OCR 模型资源，包括中文 `Hani_ctc`。为避免大图导致内存压力，读取时先按最长边约 2048 px 采样并处理照片方向，识别结束后释放位图。内置模型使通用 APK 明显增大，最终大小以交付文件为准。

## 构建与测试

最终执行：

```text
testDebugUnitTest lintDebug compileDebugAndroidTestKotlin assembleRelease assembleDebugAndroidTest
```

第二次完整构建成功，121 个任务执行/复用，退出状态成功。主机 JVM 测试 **181 项全部通过**，0 失败、0 错误、0 跳过。新增测试覆盖 OCR 文本解析的 kcal/kJ、全角字符、每份、每 50g、多列、毫升、NRV%、脂肪子项、重复行、缺失值与不确定数值；食品代码覆盖 g/份/kJ、校验失败、字段/类型/单位/范围约束及导入不覆盖历史；首次流程覆盖旧用户与新用户门禁。

Android Lint：0 错误、14 个警告、1 个信息提示。警告主要是已有依赖版本、KAPT、资源目录和 Compose 命名/装箱建议；没有阻止发布的问题。

**36 项设备测试已编译并生成测试 APK，但执行环境没有连接 Android 设备或模拟器；未运行设备测试、相机识别、真机界面操作或真实覆盖安装。** 发布前已核对旧 APK 与已发布 schema；上线后首次覆盖安装建议先导出备份并检查一条身体记录、一个食品和一条摄入仍在。

检查证据位于任务目录 `work/verification-v1.8/`：构建日志、JUnit XML、Lint 报告、迁移/schema 核对、合并 Manifest、OCR 资源清单、本地库页对齐检查与最终文件哈希。

## 签名与安装

发布配置使用工程外的原更新签名私钥，并在构建时强制校验证书 SHA-256：

```text
45bbe006cb3504fbb0e422289f4c04ab433f4e226d9985101968f5312e7b7af0
```

打包脚本会用 `apksigner` 验证新 APK 和输出目录内所有此前交付的 FitLedger APK，逐一确认同一证书和 `com.fitledger` 包名。它不会读取用户手机的安装包；若手机安装的是此前交付 APK，签名兼容即可直接覆盖。

安装顺序：在旧 App 导出备份并另存；下载 1.8 APK；选择系统的「更新/安装」；打开后确认数据存在。**不要卸载旧 App，不要清除 App 数据。** 若系统报签名冲突或降级，保留旧 App 和数据，不要卸载绕过，反馈提示文字即可。

## 主要文件

- `data/Migrations.kt`、`data/Database.kt`、`data/Backup.kt`：v6 迁移、可选肌肉、直接营养与 v9 备份。
- `ui/IntroScreen.kt`、`ui/StartupFlow.kt`、`ui/ProfileRatioEditors.kt`、`ui/Screens.kt`：首次流程、每日门禁与编辑界面。
- `domain/DirectNutrition.kt`、`ui/DirectNutritionEditor.kt`：直接营养记录。
- `data/FoodShareCodec.kt`、`ui/FoodSharing.kt`：离线食品分享代码。
- `data/NutritionPhotoReader.kt`、`domain/NutritionLabel.kt`、`ui/NutritionPhotoInput.kt`：本地 OCR、保守解析与人工确认。
- `app/src/main/AndroidManifest.xml`、`app/build.gradle.kts`：内置 OCR、文件提供者、版本与权限控制。
- `UPDATE_1.8.md`、`README.md`、`.gitignore`：使用、GitHub 上传和升级说明。
