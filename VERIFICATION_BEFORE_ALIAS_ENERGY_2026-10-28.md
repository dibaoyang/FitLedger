# 当前待发布源码检查记录

日期：2026-10-28。本轮仅实现第27项记录导出和第28项分区折叠；23项仅为方案讨论，明日预设与其余已完成待发布功能保留。没有打包，没有改动1.9.1交付文件。

执行testDebugUnitTest、lintDebug、compileDebugAndroidTestKotlin、compileReleaseKotlin（offline），BUILD SUCCESSFUL：
- 381项JVM自动测试通过，零失败／错误／跳过。
- Debug／Release代码编译通过；Lint零错误、6个既有警告。
- 111项设备测试源码编译通过，未在设备执行。没有真机覆盖升级、PNG成图、系统文件选择器或触摸测试。
- Room10及schema1～10哈希不变，无新增Migration。
- 备份14保存独立折叠偏好，兼容读取1～13；应用包名、数据库和DataStore名称保持不变。
- 26份已交付APK／源码归档哈希不变。未生成新APK、AAB或源码ZIP，开发应用版本仍暂为1.9／11。

新增检查覆盖导出隐私、所选列、kcal/kJ、消耗快照、缺失值／未知费用、日期范围、CSV转义与公式防护、折叠重启持久化与未知键保留、旧设置和备份兼容。设备测试新增PNG尺寸／格式、缺失值、长文本和过大图片保护的测试源码，当前仅编译。

证据在任务目录work/verification-export-collapse-2026-10-28/：checks.log、JUnit、Lint、summary.json、旧文件哈希和checked-source-sha256.json。本轮业务代码检查完成后只更新文档。上一轮362项／104项结果见VERIFICATION_BEFORE_EXPORT_COLLAPSE_2026-10-28.md，更早记录继续保留。