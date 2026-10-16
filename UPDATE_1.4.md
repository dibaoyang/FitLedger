# 轻衡 1.4：运动消耗、实际热量差与主页隐藏区

继续原项目，交付位于 D:\Codex\Workspaces\2026-09-17\referenced-chatgpt-conversation-this-is-an。未修改或替换 C 盘旧目录。D 盘既有源码与此前 1.3 一致后再开发。

## 使用

完成当天称重后，在首页“今日运动与额外消耗”添加运动：
- 爬坡：预设 6–10% 坡度、中快步行，7.0 MET。
- 爬楼：一般速度，6.8 MET。
- 跑步：一般跑步，8.0 MET。
- 直接填写：填写额外活动消耗，支持当前 kcal/kJ 显示单位，切换单位自动转换输入。

每项可记录多次，单条保存运动类型、时长、采用的体重、MET、kcal 结果以及是否累加。支持删除今日误记；不因修改资料/计划重算过去运动。手填方式不要求时长。

按时长估算：额外 kcal = (MET − 1) × 当天体重kg × 分钟 / 60。采用净活动消耗，扣除同时段静息能量。三项预设只代表标注强度，并非只凭时间就能准确判断个人消耗；实际速度和坡度不同，可改用设备提供的“活动消耗”。不得把设备全天总消耗再次填为额外消耗。

活动系数可能已包含日常运动。若这次运动已包含，请取消“计入今日额外消耗”，保留记录但不重复加到总消耗。App 不自动修改你的活动系数。

今日总消耗 = 原每日估算消耗 + 计入的运动消耗。
实际热量差（估算）= 今日总消耗 − 已记录摄入。
正数显示缺口，负数显示盈余。尚未记录完当天饮食时，不代表全天最终差额。身体能量快照缺失时不假定为零，不显示错误总消耗或缺口。

饮食目标及“目标减已摄入”的剩余额度继续独立保留，记录运动不会自动增加饮食目标。趋势增加额外运动消耗，总消耗/计划热量差/实际热量差纳入计入的运动。

首页隐藏的身体字段移到所有首页内容下方的“已隐藏的身体信息”区域，保持遮罩；点击该项眼睛恢复显示后回到顶部。独立隐私状态不变，趋势仍隐藏对应数值及曲线。

## 数据升级

versionName 1.4，versionCode 5，applicationId com.fitledger 不变。
Room version 3 → 4，新增 MIGRATION_3_4：仅创建 exercises 表和日期索引，保留所有旧表与记录，不伪造过去运动。原 1→2→3 链保留，旧 schema 不改。数据库名 ledger.db 和 DataStore settings 不变。

备份格式 5，支持读取 1–5。旧备份缺少运动时为空；新备份包含运动全部快照及独立隐私。恢复仍明确确认覆盖，有恢复前副本、事务和设置同步日志。

新增文件：domain/ExerciseCalculator.kt、data/Exercise.kt、ui/ExerciseSection.kt、domain/ExerciseCalculatorTest.kt。
修改：data/Database.kt、Migrations.kt、Repository.kt、Backup.kt，ui/LedgerViewModel.kt、Screens.kt、SettingsTrends.kt；app/build.gradle.kts；备份/迁移/Repository 测试；新增 Room 4.json。代码路径位于 app/src/main/java/com/fitledger，测试分别在 src/test 和 src/androidTest。

## 安装

先在手机旧版导出外部备份，再直接打开新 APK 选择更新。不要卸载、不清除应用存储。如系统报告签名冲突，保留原 App 并反馈。签名核对和编译测试结果见 BUILD_STATUS.md。

## 计算参考

预设 MET 来自 2024 Adult Compendium，查阅于 2026-10-10：
- https://pacompendium.com/walking/ ：坡行 17035、爬楼 17131。
- https://pacompendium.com/running/ ：一般跑步 12150。

以 1 MET ≈ 1 kcal/kg/hour 为估算约定，减去 1 MET 得到超出静息的净消耗。此为估算，非设备实测。
