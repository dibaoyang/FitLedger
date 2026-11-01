# 当前待发布源码检查记录

日期：2026-10-28。本轮实现第29项食品搜索别名、第30项仅热量记录；保留导出、分区折叠、明日预设及其余已完成待发布功能。第23项食品替换与32项数量倍数仅为方案讨论。没有打包，没有改动1.9.1交付文件。

执行testDebugUnitTest、lintDebug、compileDebugAndroidTestKotlin、compileReleaseKotlin（offline），BUILD SUCCESSFUL：

- 397项JVM自动测试通过，零失败／错误／跳过，51个测试类报告。
- Debug／Release代码编译通过；Lint零错误、6个既有警告。
- 120项设备测试源码编译通过，未在设备执行。没有真机覆盖升级、触摸操作或系统文件选择器实测。
- Room10→11：显式MIGRATION_10_11只添加foods.aliases TEXT NOT NULL DEFAULT ''，旧食品默认为无别名。完整1→11迁移链保留，无破坏性迁移。
- 真实SQLite检查旧schema1～10逐一升级到11，历史原列与表内容保留，目标结构与新schema11一致；另以所有表已有数据检查10→11保留身体、目标、摄入、预设、自动添加标记、运动、食品、分区、组合、设置恢复日志等。
- schema1～10及26份已交付APK／源码ZIP哈希不变，已生成schema11。应用包名com.fitledger、数据库ledger.db、DataStore settings及签名配置保持。
- 备份15保留别名及仅热量记录，兼容读取1～14；未知三大营养写JSON null，拒绝把未知当0或以旧元数据有损降级保存。
- 未执行assemble或APK打包任务，未生成新APK、AAB或源码ZIP。开发应用版本仍暂为1.9／11，等待后续明确打包指令。

新增16项JVM测试覆盖：别名解析、校验、搜索、公开食品码不含私有别名；备份15往返、旧备份默认值、未知宏量null与损坏数据拒绝；宏量汇总不完整、热量与费用保留；导出未知单元格和宏量曲线空值；10→11真实SQLite保留检查。

新增9项设备测试源码覆盖：称重／目标门禁、历史仅热量记录改删与补全、身体和目标不变、推荐及旧方案采用拒绝未知营养、复制倍数、撤销、未知费用、失败原子回滚和每日固定食品／预设共存。这里只编译，未执行，不作为真机验证结果。

只读复核发现并修正了推荐区域暂停时必吃选择可能丢失的问题，补全或移除仅热量记录后仍保留原选择。主源码检查后仅更新说明文档。

证据位于任务目录work/verification-alias-energy-2026-10-28/：checks.log、compile.log、JUnit XML、Lint XML、summary.json、旧交付与schema哈希、checked-source-sha256.json。上一轮381项／111项结果保存在VERIFICATION_BEFORE_ALIAS_ENERGY_2026-10-28.md，更早记录继续保留。
