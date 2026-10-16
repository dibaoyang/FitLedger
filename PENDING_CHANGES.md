# 待打包修改 · 2026-10-13

> 状态更新：用户随后明确要求“把其他的功能打包好”，已授权将下列修改纳入 1.6 / 7。拍照识别未加入。本文件保留打包前检查记录，正式发布结果见 `BUILD_STATUS.md` 和 `UPDATE_1.6.md`。

用户要求先积累需求，直到明确说“打包”才生成安装包。本轮仅修改现有 D 盘工程、编译源码和运行检查，不生成 APK 或源码压缩包，不安装或操作手机。

## 功能

- 每日强制身体录入中可选 kg（公斤）或斤；1 kg = 2 斤。切换会换算当前输入，使用十进制转换保留精度。无效的非空输入须先修正，不能仅更换单位标签。肌肉量、瘦体重及历史展示仍使用 kg。
- 单位选择在 DataStore 持久保存，在设置中也可以修改。进入存储、BMR、瘦体重、营养目标计算前统一转换为 kg。
- 今日饮食记录新增“按今日体重填入（1 g/kg）”，仅用于单位为 g 的食品。今天体重 60 kg（120 斤），即填入 60 g；使用今天已保存的身体记录，不使用昨日记录、界面格式化值或食品每份基准量。
- 快捷按钮仅填入数量，可自行修改，再点击“记录到今日”保存。切换食品或日期不沿用上一份草稿；不会自动应用食品推荐的步长或上限。
- 设置新增“按今日体重快捷填入食品克数”开关，默认开启。旧版本缺少此设置时使用默认值，不修改任何现有食品或摄入。
- 隐藏体重时快捷填入先提示食品克数将间接显示体重，明确确认后才显示此次数量；不改变体重的独立隐藏状态。先填入后隐藏体重时遮罩快捷数量，需确认显示或改为手动输入才能记录。
- 首页“今日运动与额外消耗”及额外运动合计移动到页面最底部，位于隐藏身体信息之后。顶部继续显示总消耗及热量差，仍按原数据计算。

## 数据与发布

- `applicationId` 仍为 `com.fitledger`；版本字段暂留 `1.5 / 6`，正式打包时再按升级要求处理。
- Room 仍为 v4，不修改实体、数据库名称、历史 schema 或 Migration；现有 1→2→3→4 迁移链保留。
- DataStore 仍为 `settings`，增加 `weight_input_unit`、`weight_food_shortcut` 两个独立键。旧设置缺省为 KG / true，读取不会清空设置。
- `PreferencesCodec` 和恢复流程覆盖两个新设置，保留未知 DataStore 键。仅增加可选设置，备份版本继续为 v6，旧备份正常兼容；用旧版程序恢复新版备份时会忽略其不认识的两个新偏好。
- Room 快照、摄入、运动、食品和计划逻辑不变。签名配置和原私钥保持不动，本轮不产生新的签名验证或覆盖安装结论。
- iOS 与原生鸿蒙支持仅讨论可行性，未加入跨平台实现。

## 检查

仅执行 `compileReleaseKotlin testDebugUnitTest lintDebug compileDebugAndroidTestKotlin`，不执行 APK 或应用 bundle 生成任务。

- 编译和检查成功：`BUILD SUCCESSFUL in 1m 58s`。
- 86 项主机测试全部通过，0 失败/错误；新增 12 项覆盖 kg／斤精度、BMR/瘦体重/目标一致性、克数捷径、旧备份缺省、新设置备份往返、真实 DataStore 文件关闭重开持久化及恢复保留未知设置。既有 SQLite 历史迁移与备份测试继续通过。
- Android Lint：0 错误，3 个原有非阻断提示。
- 19 项设备测试编译通过；本轮未运行真机/模拟器测试，未实测界面或覆盖安装。
- 与上一份 `FitLedger-v1.5-grouped-source.zip` 逐字节核对，`Database.kt`、`Repository.kt`、`Migrations.kt`、`Backup.kt`、全部 4 份历史 schema 和 `app/build.gradle.kts` 均未变化。
- 检查前后全部已有 APK 的路径、大小、修改时间和 SHA-256 相同，没有新 APK。

日志与验证结果保存在工程所在任务根目录的 `work/verification-weight-input/`。上一份已交付 APK 的构建结果仍见 `BUILD_STATUS.md`，不要把该文件的旧 APK 信息当作本轮打包结果。

## 本轮文件

- 新增 `domain/WeightUnit.kt`：输入单位转换与按 kg 填克数的规则。
- 修改 `data/Settings.kt`：两个新偏好、序列化和恢复；为实际文件持久化测试提供内部 DataStore 注入入口。
- 修改 `ui/Screens.kt`：称重单位、食品快捷量与隐私确认、页面顺序。
- 修改 `ui/SettingsTrends.kt`、`ui/LedgerViewModel.kt`：设置开关和写入入口。
- 修改 `ui/ExerciseSection.kt`：运动合计随运动区域移动。
- 新增 `WeightUnitTest.kt`、`WeightPreferencesTest.kt`。
- 更新 `README.md`、`AGENTS.md` 并新增本文件，明确等待用户通知后才打包。
