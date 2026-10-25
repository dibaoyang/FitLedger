# 2026-10-25 源码开发检查记录

> 本文是打包前的源码检查记录，以下版本与未打包状态指当时的检查点。用户随后授权发布 **1.9／11**，当前发布结果见 [BUILD_STATUS.md](BUILD_STATUS.md)。

本批在既有 D 盘 FitLedger 工程中继续开发。已完成源码与检查，**没有生成 APK、AAB 或源码 ZIP**。`BUILD_STATUS.md` 仍记录最后发行的 1.8.1，不能将本记录理解为新安装包已发布。

## 版本与数据

| 项目 | 本批状态 |
| --- | --- |
| versionName／versionCode | 保持 1.8.1／10，等待以后获准打包再增加 |
| applicationId | 保持 `com.fitledger` |
| Room | 源码 6→7，增加 `MIGRATION_6_7`，完整保留 1→7 迁移链 |
| 数据库文件 | 保持 `ledger.db` |
| DataStore | 保持 `settings`，增加推荐策略键，不清空已有键 |
| 备份 | 格式 9→10，读取旧版默认未分区、未分类和未知价格 |
| 签名与旧安装包 | 未修改签名配置；本批不产生新 APK，因此不声称已完成新 APK 签名或手机覆盖安装验证 |

新增数据库内容包括种类分区、排序、营养标签、食品单价、摄入费用、每日固定食品及每日执行标记、放纵日。没有重算历史身体记录、营养目标、营养摄入或费用。未知历史费用保持 null，不能当作免费，也不能按今天的价格回算。

备份恢复继续先校验、确认并保存恢复前副本，再事务替换数据并完成设置同步。删除某次自动摄入后保留执行标记；恢复时同时保护模板和摄入的自增编号，避免已删除记录的编号复用。

## 主要修改文件

| 文件或范围 | 用途 |
| --- | --- |
| `data/Database.kt`、`data/Migrations.kt`、`app/schemas/.../7.json` | 新实体、兼容字段、排序与迁移；schema 1～6 保持原样 |
| `data/Repository.kt`、`data/FoodZoneQuotas.kt` | 分区管理、事务排序、摄入价格快照、采纳推荐前使用当前数据再次核对 |
| `data/DailyEating.kt` | 每日固定食品、只添加一次、今日改量、放纵日 |
| `data/Backup.kt` | 备份 10、旧备份兼容、新数据验证与恢复 |
| `data/Settings.kt`、`data/RecommendationPolicyCodec.kt` | 推荐预算及宏量容差的 DataStore 保存、读取和备份 |
| `domain/Models.kt`、`RecommendationSearch.kt`、`RecommendationPolicy.kt`、`QuotaMath.kt` | 预算、营养上下限、价格偏好、锁定、分区上限及小数精度 |
| `domain/FoodPricing.kt`、`domain/TrendSummary.kt` | 包装价格换算、费用检查、累计变化及平均缺口筛选 |
| `ui/LedgerViewModel.kt`、`ui/Screens.kt` | 五栏导航、今日布局、实际缺口放大、摄入入口与推荐接入 |
| `ui/FoodLibraryScreen.kt`、`FoodZones.kt`、`DragSortColumn.kt` | 食品库分区、多标签过滤、价格表单、独立两层长按排序 |
| `ui/DailyFoodsScreen.kt`、`IntakeGroups.kt`、`GroupedIntakes.kt` | 每日设置、自动与手动摄入合并、逐条改量和删除 |
| `ui/TrendSummarySection.kt`、`SettingsTrends.kt`、`RecommendationSettingsSection.kt` | 总结、隐私、平均值样本与推荐约束设置 |
| `ui/IntroScreen.kt`、`FoodSharing.kt` | 更新五个页面说明；食品代码保持旧格式，说明本地价格和分区不随旧码分享 |
| `app/src/test`、`app/src/androidTest` | 计算、兼容、迁移、备份、持久化及事务测试 |
| `README.md`、`AGENTS.md`、本批公告 | 区分最后发行版与尚未打包源码，保留用户自写简介及 QQ 文案 |

源码文件均位于 `app/src/main/java/com/fitledger` 下，表中另有明确位置的文件除外。

## 检查结果

执行 `testDebugUnitTest lintDebug compileDebugAndroidTestKotlin compileReleaseKotlin --offline`，最终 **BUILD SUCCESSFUL**。

- **271 项本地测试通过**：0 失败、0 错误、0 跳过。
- 实际 SQLite 测试从每个已发行 schema 1～6 升级至 7，核对旧记录的列值、索引、自增编号和数据库完整性。
- 覆盖多标签及价格备份、旧费用未知、模板只执行一次与删除不重生、全新数据库恢复编号不复用、预算扣除已吃费用、已吃价格不随食品编辑改变、陈旧推荐拒绝、全部宏量双向容差、锁定及共享分区上限。
- 补充测试避免食品很多时漏掉明显的单食品解；统一小数份数上限的浮点误差处理，零预算和零上限保持严格。
- Debug、Release 业务代码编译通过；**67 项设备测试代码编译通过，但没有在设备上执行**。
- Android Lint：**0 错误、6 个既有警告、1 个信息提示**。警告仍为原有 Compose 命名、资源目录、KAPT 建议及图标形状／单色图层提示。
- 对照开发前哈希，**25 份原有 APK／归档未改变，没有新增 APK／AAB／源码 ZIP；6 份旧 schema 未改变**。

原始日志、JUnit／Lint 结果及哈希核对位于任务目录 `work/verification-food-zones-2026-10-24` 和工程构建报告目录。

## 尚未验证与下次打包

当前没有连接 Android 手机或模拟器，未实际执行覆盖安装、触摸拖拽、系统文件选择器或真机恢复。源码和 SQLite 校验不能代替这些设备检查。

推荐为有时间和评估次数上限的近似搜索，返回前会严格验证配置约束；“未找到”不等于数学上不存在可行组合，也不能保证全局最低价。

用户授权打包后，应提高 versionCode，使用既有原签名密钥，并再次核对新旧 APK 包名和证书。升级前先在旧 App 导出备份并另存，再直接覆盖安装；不能卸载旧 App、清除数据或使用破坏性迁移。若签名不兼容，应保留旧安装并排查。
