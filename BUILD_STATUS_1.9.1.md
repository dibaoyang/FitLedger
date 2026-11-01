# 轻衡1.9.1 同单位分区修正版

整理日期：2026-10-28；编译及自动检查完成于2026-10-27。用户尚未下载上一份1.9.1，明确要求修正后保持版本号重新打包。本次APK与源码均使用同一份已检查工程。

| 项目 | 结果 |
| --- | --- |
| versionName／versionCode | **1.9.1／12**，按要求保持不变 |
| applicationId | **com.fitledger**，不变 |
| Room／备份格式 | **8／11**，本次规则修正不再变化 |
| 新增Migration | 本次修正无；相对1.9仍使用原有显式7→8及完整1→8迁移链 |
| APK | `FitLedger-v1.9.1-update.apk`，release、非debuggable |
| APK大小／SHA-256 | 52601354字节；`ea62394aabd3bbd4e2507e89346d09087e1cee99b269f7c9a64b9e52aa43f6f1` |
| 源码ZIP | `FitLedger-v1.9.1-source.zip`，包含完整源码、schema、测试和说明 |
| 平台 | Android8.0／API26起，目标API35 |

## 修正后的规则

- g分区只接受原生单位为g的食品；份分区只接受按份记录的食品。按原生数量1:1合计，不再用每份克重或分区份换算混放。
- 每日上限和单次推荐上限分别填写，单位跟随该分区。例如每日300g、单次150g，或每日3份、单次2份；单次留空仍跟随每日上限，并扣除今日已经摄入的量。手动摄入不受推荐限量约束。
- 分区内搜索、多选只提供同单位食品；移动来源先提示，一次事务保存。添加、编辑、推荐和采纳均检查单位，不能绕过界面筛选混入另一单位。
- 旧分区有混合单位或旧份数换算时会显示待整理，暂停该区推荐；可经确认将不匹配食品移至未分区，再核对单位、重新填写上限并保存。没有自动删除食品、转换旧限量或改写摄入记录。

## 文件与兼容

主要修改 `FoodZoneQuotas.kt`、`Repository.kt`、`ZoneFoodPicker.kt`、`FoodZones.kt`、`FoodLibraryScreen.kt`、`Screens.kt`、`LedgerViewModel.kt` 及对应测试、README、GitHub文案和更新说明。

本轮没有修改实体、Room schema、Migration或备份格式；旧换算字段保留存储和备份兼容，推荐不再使用其跨单位换算。热修工程schema1～8、待发布主工程schema1～9全部哈希不变。数据库仍为 `ledger.db`，DataStore仍为 `settings`，没有destructive migration或启动清空逻辑。历史身体、DailyTarget、饮食、运动、营养和费用快照保留。

前一份同版本APK、源码ZIP及源码导出文件夹在替换前归档至任务目录 `work/replaced-v191-before-strict-2026-10-27/`。此前1.9及更早发行文件原样保留。其他尚未发布的功能继续保留在主开发工程，没有随本次热修打包。

## 检查结果

执行 `testDebugUnitTest lintDebug compileDebugAndroidTestKotlin assembleRelease --offline`，**BUILD SUCCESSFUL**。

- **282项本地测试通过：0失败、0错误、0跳过**。包括真实SQLite完整旧版本升级及记录保留、备份兼容、严格同单位累计、每日扣除和单次上限、旧分区暂停、锁定与采纳复验。
- **76项Android设备测试代码编译通过，未在设备执行**。覆盖批量添加／移出事务、失败回滚、旧字段及摄入快照保留。
- Android Lint：**0错误、5个既有警告、1个信息提示**；release Lint Vital通过。
- 图标原图不变；Manifest无新增联网、相机或全相册权限；版本码12及包名核对通过。
- 使用原工程外更新密钥。包含新版的13份已交付APK签名与包名一致，旧正式版本均低于版本码12。证书SHA-256：`45bbe006cb3504fbb0e422289f4c04ab433f4e226d9985101968f5312e7b7af0`。

没有连接手机或模拟器，未做真机覆盖安装或触摸交互测试。签名对比针对此前交付文件，未直接读取手机已安装应用的证书。原始证据见任务目录 `work/verification-v1.9.1-strict/`。

**安装前先在旧App导出备份并另存，然后直接覆盖安装。不要卸载旧App，不要清除应用数据。** 若手机安装的是此前交付的1.9或更早APK，本包满足同包名、同证书和更高版本码的更新条件。新版备份不能导回旧版App。

源码ZIP不含签名私钥、真实用户数据库／备份、APK、构建缓存或本地SDK配置。使用方式见[README](README.md)，朋友版说明见[更新公告](UPDATE_1.9.1.md)。