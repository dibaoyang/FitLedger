# 轻衡1.10发布构建

> 2026-11-01源码新增的一批功能及Room13／备份17兼容说明见[PENDING_38_47.md](PENDING_38_47.md)，尚未打包；下方仍为旧1.10的交付记录。

> 此文件保留已交付1.10的发布记录。2026-10-30继续开发的Room12／备份16功能尚未打包，见[PENDING_RECIPES_WEEK_RATIOS.md](PENDING_RECIPES_WEEK_RATIOS.md)；下方旧安装包和源码ZIP未被覆盖。

公告修订（2026-10-29）：UPDATE_1.10.md现为1.9→1.10的完整合并公告，统一包含1.9.1的修复、新增功能和1.10后续更新，适用于1.9及1.9.1用户。同步README和GitHub发布说明，提供FitLedger-v1.10-source-complete-notes.zip。本次只改说明文档，APK、版本号、数据库和所有之前交付文件保持不变，无需为公告修订重新安装。

日期：2026-10-28。用户明确要求「不要32，其他的打包」。本次在原D盘工程发布全部已完成的后续功能，不包含第32项数量倍数按钮，也不包含食品替换、预设与实际对比等未完成讨论项。旧1.9构建记录保存在BUILD_STATUS_1.9.md，旧版交付文件全部保留。

## 安装包与源码

| 项目 | 结果 |
| --- | --- |
| versionName／versionCode | **1.10／13**（前版1.9.1／12） |
| applicationId | **com.fitledger**，保持一致 |
| APK | FitLedger-v1.10-update.apk，release、非debuggable |
| APK大小 | **52,949,006字节**，约52.95MB／50.50MiB |
| APK SHA-256 | f4baf2988ac5b82cfd0841b16941204af0bc2cfde6665d7e55acedc027d829d9 |
| 源码ZIP | FitLedger-v1.10-source.zip；含工程、Gradle Wrapper、schema1～11、测试、图标与说明文档 |
| Android要求 | Android8.0／API26起，目标API35 |

文件位于D:\Codex\Workspaces\2026-09-17\referenced-chatgpt-conversation-this-is-an\outputs。源码包排除私钥、数据库、真实备份、SDK、本地配置、安装包与构建缓存，解压后入口为FitLedger。两个交付文件的校验值另见同目录FitLedger-v1.10-SHA256.txt。

## 本次更新

完整入口表和适合发给朋友的公告见[UPDATE_1.10.md](UPDATE_1.10.md)。包括历史饮食／运动补改、撤销与食品回收站、常吃组合、历史选择复制、收藏／搜索／常用数量、推荐诊断与试算、多方案10～50套对比、分区独立混选、食品比价与费用回顾、灵活每日安排、桌面小组件、明日预设、CSV／PNG导出、分区折叠、食品搜索别名、仅热量记录。

继续保留1.9.1原生g／份分区规则和直接选食品入区。仅热量记录的碳蛋脂未知，不按0汇总；宏量趋势留空，导出标未知，补全前暂停按剩余营养推荐，热量与实际缺口正常计算。推荐属于有限搜索，不保证全部可行组合或全局最优。

## 数据升级

- Room数据库为**11**。从已交付1.9.1的**8**通过显式**8→9→10→11**升级，保留完整1→11迁移链和schema1～11。
- 8→9增加收藏、快捷量、组合、灵活安排、暂停和分区混选存储；9→10增加预设表与索引；10→11只增加foods.aliases，旧食品默认为空别名。均不重算旧身体、目标或摄入快照。
- 已交付1.9.1源码中的schema8与本工程schema8逐字节一致，SHA-256为0b47dfeb2167d1ecb9f86be65e271a2f9d7cf46a60e7e2ab821bf6dcb1389a72；7→8执行SQL相同。
- 数据库仍为ledger.db，DataStore仍为settings，没有destructive migration、启动清库或清空偏好。
- 备份格式**15**可读1～14。新字段使用兼容默认值；未知费用不当免费，未知宏量写JSON null并严格保留标记；新版备份不能导回旧App。
- 恢复继续使用原有完整校验、确认、恢复前副本、Room事务与设置同步恢复日志。

## 签名与覆盖安装

使用工程外的原更新密钥，Gradle签名保护通过。apksigner校验新版及全部旧交付，共**14份APK**：证书SHA-256一致，包名均为com.fitledger，新版versionCode13高于全部旧包；aapt确认新版不可调试。

证书SHA-256：45bbe006cb3504fbb0e422289f4c04ab433f4e226d9985101968f5312e7b7af0

核对对象是此前交付的APK文件，未直接读取手机上已安装版本的证书。若当前安装的是此前交付包，本版满足同包名、同证书和更高版本码的覆盖升级条件。

**先在旧App导出备份并另存到App外，再打开新APK选择「更新／安装」。不要卸载旧App，不要清除应用数据。** 如果系统提示签名冲突或降级，请保留旧App和数据并核对文件，不要通过卸载绕过。升级后的数据库不要降级给旧APK使用。

## 验证结果与实际范围

执行testDebugUnitTest、lintDebug、compileDebugAndroidTestKotlin、assembleRelease（offline），**BUILD SUCCESSFUL，3分20秒**。

- **397项JVM测试通过**：零失败、错误、跳过。
- Debug与Release代码编译、Android Lint和Release Lint Vital通过；Lint零错误、6个既有警告、1个信息提示。
- 真实SQLite逐一检查schema1～10升级到11，核对历史原列、表、索引、自增编号和完整性；另外检查已发行8→9及所有表已有数据的10→11保留。
- **120项设备测试源码编译通过，未执行**。未连接Android设备或模拟器，没有实际手机覆盖升级、触摸、系统文件选择器、PNG成图或相机测试。
- **177个业务源码、资源、测试、schema及图标文件在打包前后哈希不变**；本轮只改安装版本和发布说明。26份旧APK／源码ZIP哈希不变。
- 最终Manifest没有新增联网、相机或全相册权限。选定图标原图不变。

证据在任务目录work/verification-v1.10：release.log、JUnit、Lint、summary.json、APK元数据、14份签名核对、原文件哈希与交付校验信息。源码检查历史仍保留在VERIFICATION_AFTER_1.9.md及各VERIFICATION_BEFORE文件中。
