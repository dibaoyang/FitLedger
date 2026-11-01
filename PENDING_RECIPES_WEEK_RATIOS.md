# 1.10之后的配方、预算与比例改动（开发记录，已纳入1.11）

更新日期：2026-10-30。以下保留本批开发阶段说明；2026-11-01用户明确要求打包，本批已与后续改动一起纳入1.11／versionCode14。最终交付为Room13／备份17，见[构建记录](BUILD_STATUS.md)；本文中的Room12／备份16为当时的中间开发版本，原1.10交付文件保留。

## 功能与入口

| 采用项 | 功能 | 使用方式 |
| --- | --- | --- |
| 14 | 自制食品配方 | 食品库切换到「自制食品配方」，选择食材与用量，填写成品实际总重量，预览整份、每100g的营养和成本。可以只保存配方，也可以确认生成／更新关联食品。 |
| 33 | 未来1～7天预设 | 在预设界面选择未来日期。预估缺口沿用创建时参考日已保存的总消耗，不创建未来身体数据或目标；到当天核对并确认后才计入摄入。 |
| 34 | 周饮食预算 | 设置→「周预算、轮换、整套组合与比例」。按周一至周日累计已记录饮食费用，可选择把剩余额度按剩余天数均分，和日预算共同约束推荐。 |
| 35 | 减少重复推荐 | 在推荐扩展设置填写近几天的轮换范围。最近吃过的食品降低优先级；锁定、营养上下限和预算等限制仍优先。 |
| 36 | 整套组合参与推荐 | 勾选常吃组合，使食品整套出现并共用倍率，也可整套不选；参与的组合不能共用食品，最多8套。手动记录仍可按实际选择。 |
| 37 | 批量编辑食品 | 食品库→「批量编辑」，选择食品，统一设置收藏、是否允许推荐、多选营养标签。先预览再保存，其他字段不改。 |
| 新增 | 食品重量比例 | 推荐扩展设置、常吃组合、未来预设中均可配置，例如A重量＝B重量×0.5；按食品调整单位就近取整，显示目标、实际与偏差。 |

按g食品直接用克重。按份食品必须在食品编辑中明确填写「每份重量g」才可建立重量比例，未知不能按0推算。每份重量只用于重量换算，不改变g／份分区原生限量。

## 数据与约束

- 新设置默认关闭，保留原有计划和推荐路径。每日身体录入、目标历史快照、kcal内部计算、独立隐私等原有规则保留。
- 配方食材保存营养和费用快照；食品资料改变后需明确刷新。成品重量按实称总重量分摊，不自动估计烹饪损耗。未计价食材使成品价格保持未知。
- 配方生成／更新食品只改变今后的食品资料，不重算旧摄入或DailyTarget。删除配方保留已生成食品和历史记录。
- 周预算只扣实际摄入，未吃的预设不扣额。历史费用不按现价重算；本周有未计价摄入时不能保证周预算，需补全费用或明确关闭限制。
- 比例允许食品步长产生的近似偏差，仍不放宽推荐锁定量、食品最大量、分区共享上限和同区互斥。冲突时明确提示，不把任意偏差当作有效比例。
- 多方案表仍可展示营养／预算未满足的试算。采用前重新验证；周预算需到设置明确调整，不会偷偷改成日预算或自动放宽。
- 未来预设独立保存，到当天确认后才产生实际摄入。修改规则、配方、预设和批量编辑不改写历史身体与目标。
- 批量编辑和比例预览采用前检查食品、模板和预设是否变化，避免旧界面覆盖新资料。
- 有限搜索会去重，但不保证全局最优或每次都找到可行解；10套起、最多50套的对比规则保留。

## 兼容性

本批源码Room版本从11升至12，新增显式`11→12`迁移：创建`food_recipes`及其关联食品唯一索引，给`food_combos`和`food_preset_plans`增加默认空的`ratioRules`。保留schema1～11和完整1→12迁移链，不使用破坏性迁移。

备份版本由15升至16，保存配方、比例、多日预设与推荐扩展设置，仍读取1～15版备份。新增功能不能降级写入旧格式。DataStore只新增`recommendation_extras`键，不清空旧设置；原有恢复确认、事务和设置恢复日志保持有效。

`applicationId=com.fitledger`、`ledger.db`、`settings`及原签名配置不变。尚未生成升级包；不能用旧1.10 APK验证本批功能，未来打包需要提高versionCode并再次检查签名。

## 检查记录

2026-10-30完成以下检查，最终统一任务成功退出：

| 检查 | 实际结果 |
| --- | --- |
| 本机JVM测试 | 57组、453项，0失败、0错误、0跳过；较已发布1.10增加56项 |
| 数据库升级 | 实际SQLite逐一执行所有旧schema1～11到12的完整迁移，比较列、索引、历史记录和完整性；11→12额外检查旧表、快照、自增编号、默认值、关联食品唯一索引 |
| 备份／DataStore | 验证备份16完整往返、旧15格式缺失新增字段时的兼容、原有旧格式兼容测试、损坏及降级拦截；实际DataStore文件关闭重开后保留新旧设置和未知键 |
| 编译 | debug／release主程序Kotlin和Android测试Kotlin均通过；没有执行assemble／bundle安装包任务 |
| Android Lint | 0错误、6警告、2提示；警告为既有命名、资源目录、kapt和图标事项，不阻塞本次检查 |
| Android迁移样本SQL | 另在本机SQLite核验9组初始化样本、68条SQL、468项字段及完整性检查；属于样本验证，不算手机测试 |
| Android测试 | 145项测试源码编译通过，**未在设备执行**；当前没有连接手机／模拟器，也没有验证真实覆盖安装或界面操作 |
| 旧文件保护 | 30个已有APK／源码压缩包的SHA-256全部保持不变；schema1～11校验值全部保持不变 |

统一任务：`testDebugUnitTest lintDebug compileDebugAndroidTestKotlin compileReleaseKotlin`，离线执行。Room12导出schema已生成；SHA-256为`ea25b5f54d3507edfaf189b95344698ca93d36394bce8eb5de18f08f0576940e`。

原始日志及JSON结果位于项目外的`D:\Codex\Workspaces\2026-09-17\referenced-chatgpt-conversation-this-is-an\work\verification-recipes-week-ratios-2026-10-29`，最终日志为`checks.log`，结果为`results.json`。目录日期是本批开始日期，最终检查日期为2026-10-30。

## 主要代码文件

| 范围 | 新增／修改文件 |
| --- | --- |
| 配方、批量编辑 | `data/Recipes.kt`、`ui/RecipesScreen.kt`、`ui/BatchFoodEditor.kt`、`ui/FoodLibraryScreen.kt` |
| 比例、周预算、轮换和组合 | `domain/WeightRatios.kt`、`data/RecommendationExtras.kt`、`data/FeatureStore.kt`、`ui/WeightRatioEditor.kt`、`ui/RecommendationExtrasSection.kt`、`ui/FoodCombosScreen.kt` |
| 多日预设 | `data/FoodPresets.kt`、`data/FoodPresetStore.kt`、`ui/FoodPresetsScreen.kt`、`ui/DailyFoodsScreen.kt` |
| 数据、恢复和界面衔接 | `data/Database.kt`、`data/Migrations.kt`、`data/Settings.kt`、`data/Backup.kt`、`data/Repository.kt`、`ui/LedgerViewModel.kt`、`ui/Screens.kt`、`ui/SettingsTrends.kt`、`ui/RecommendationComparisonSection.kt` |
| 实际记录导出文案 | `data/RecordExport.kt`、`ui/RecordExportScreen.kt`（说明未来预设不当成实际摄入导出） |
| 结构和验证 | 新增`app/schemas/com.fitledger.data.LedgerDatabase/12.json`及相关JVM／Android测试；保留1～11原始schema |

上表Kotlin路径均相对于`app/src/main/java/com/fitledger`。源码仍在原D盘工程`outputs/FitLedger`，没有另建独立项目。
