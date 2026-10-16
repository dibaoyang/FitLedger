# 轻衡 1.5 食品分组补充构建

日期：2026-10-11。按用户明确要求保持 versionName 1.5 / versionCode 6。全部改动及交付位于 D 盘既有工程。

构建执行：assembleRelease testDebugUnitTest lintDebug assembleDebugAndroidTest --offline
BUILD SUCCESSFUL in 1m 7s
121 actionable tasks: 34 executed, 87 up-to-date

74 项单元测试通过，0 失败/错误，其中新增 IntakeGroupsTest 5 项：多次求和、不同食品和单位隔离、食品营养快照、逐次删除后的总量与空组、跨日隔离及份数。既有生日、缺口、运动、备份、SQLite 迁移和 DataStore 测试均通过。

Android Lint：0 错误，3 个非阻断提示。19 项设备测试编译完成，但未在真机/模拟器执行；食品分组交互与手机覆盖安装尚未实测。

使用 Kotlin in-process 编译策略避免此前 daemon 对受限临时目录的写入，不改变应用依赖或功能。

APK：FitLedger-v1.5-grouped-update.apk，release、非 debuggable；applicationId com.fitledger；versionName 1.5、versionCode 6；最低 API 26，目标 API 35。

apksigner 验证通过，与此前交付的 1.5 包签名证书一致：
45bbe006cb3504fbb0e422289f4c04ab433f4e226d9985101968f5312e7b7af0

本次 APK SHA-256：
da310e83dbae445fbec419a21407d70618a0596fb3de64cd7c98bd4737d084dd

Room 仍为 v4，无新增 Migration。数据层、备份设置和版本配置与原 1.5 源码 ZIP 逐字节核对一致，详见 verification-v1.5-grouped/data-and-version-unchanged.txt。仅 UI 分组，不物理合并摄入；仍通过原记录 ID 和当天限制逐条删除。

本次文件名含 grouped 以区别前一份未安装的 1.5 包，内部版本号按用户要求保持不变。请使用本次下载链接，先导出备份，再直接覆盖安装，不卸载、不清除存储。证书比较针对先前交付的原 APK，未读取手机当前安装证书；若系统提示签名冲突，请保留旧 App 并反馈。

构建日志、JUnit XML、Lint、包身份和签名证据保存在工程同级 verification-v1.5-grouped/。上一轮记录保存在 BUILD_STATUS_1.5-before-grouping.md，使用说明见 UPDATE_1.5_GROUPED.md。
