# 轻衡1.11发布构建

日期：2026-11-01。用户明确要求「打包吧」。在原D盘工程将两批已完成的功能一起打包，旧1.10构建记录保留在BUILD_STATUS_1.10.md，全部旧交付文件保留。

## 安装包与源码

| 项目 | 结果 |
| --- | --- |
| versionName／versionCode | **1.11／14**（前版1.10／13） |
| applicationId | **com.fitledger**，保持一致 |
| APK | **FitLedger-v1.11-update.apk**，release、非debuggable |
| APK大小 | **53,161,998字节**，约53.16MB／50.70MiB |
| APK SHA-256 | ea0f8861d735489cdb6724fdc4ba67eed3469a04b8432e10eb7aa75a02699d74 |
| 源码ZIP | **FitLedger-v1.11-source.zip**，工程、测试、schema1～13、Gradle Wrapper、图标和说明文档 |
| Android要求 | Android8.0／API26起，目标API35 |

文件位于D:\Codex\Workspaces\2026-09-17\referenced-chatgpt-conversation-this-is-an\outputs。源码解压后入口为FitLedger；排除签名私钥、真实数据库、真实备份、SDK、本地配置、安装包及构建缓存。安装包与源码的最终校验值见同目录FitLedger-v1.11-SHA256.txt。

## 本次更新

完整的朋友式公告和16项功能入口见[UPDATE_1.11.md](UPDATE_1.11.md)。本次包含自制配方、制作批次与分装、未来1～7天预设、周预算、减少重复推荐、整套组合推荐、重量比例、批量编辑、待吃／已吃、推荐条件档案、食品最小量、自定义食品周期、条件搭配、跨日期搜索、自定义准备条件与整数份兼容。

推荐复验后进入待吃，点「已吃」才计入实际摄入。食品周期从该食品已保存的最早实际摄入日开始，默认7天、可选1～366天，同日多次只算一个出现日；周饮食预算仍按周一至周日统计。新份数为整数，旧的小数份、身体、摄入、目标、配方等快照保留原值。未知费用和未知宏量继续保留未知状态。

没有加入食品替换、预设与实际对比、整周模板、CSV食品导入或已取消的数量倍数按钮。开发过程记录见PENDING_RECIPES_WEEK_RATIOS.md和PENDING_38_47.md。

## 数据升级

- Room为**13**，与打包前已检查源码一致；从旧1.10的Room11通过显式**11→12→13**升级。更早安装使用完整**1→13**迁移链。
- **11→12**：创建food_recipes及关联食品唯一索引，给food_combos和food_preset_plans增加默认空字符串的ratioRules。
- **12→13**：foods增加默认0的minimum与默认空字符串的preparationTags；创建pending_foods、recipe_batches及索引。已有身体、目标、饮食、配方和偏好不因普通升级被重算或清空。
- schema1～13在本次发布构建中保持不变；旧1.10源码ZIP中的schema1～11与当前文件逐字节一致。真实SQLite已逐一验证旧schema1～12到13的迁移与历史值保留，含旧小数份、配方、自增编号和设置恢复日志。
- 数据库仍为ledger.db，DataStore仍为settings，没有destructive migration、启动清库或清空偏好。
- 备份格式**17**，可读旧**1～16**。新增待吃、批次、档案、规则和食品字段随完整备份保存；明确恢复继续采用完整校验、确认、恢复前副本、Room事务和设置同步恢复日志。待吃确认标记涉及的摄入编号下限也会保留。
- 新版备份不能导回旧App；升级后的数据库不要降级交给旧APK使用。

## 签名与覆盖安装

使用工程外的原更新密钥，Gradle原证书保护检查通过。apksigner对新版及全部旧交付共**15份APK**验证：证书SHA-256一致，包名均为com.fitledger；新版versionCode14高于全部旧包。aapt确认新版不可调试，没有新增网络访问权限。既有离线识别模型继续内置在安装包中。

证书SHA-256：45bbe006cb3504fbb0e422289f4c04ab433f4e226d9985101968f5312e7b7af0

核对对象为此前交付的APK文件，尚未从手机读取已安装应用的证书。若手机安装的仍是此前交付包，本版满足同包名、同证书和更高版本码的覆盖升级条件。没有实际执行手机覆盖升级或触摸测试。

**先在旧App导出JSON备份并另存到App外，再打开新APK选择「更新／安装」。不要卸载旧App，也不要清除应用数据。** 若系统提示签名冲突或降级，保留旧App和数据并核对文件，不要通过卸载绕过。

## 验证结果与实际范围

| 检查 | 实际结果 |
| --- | --- |
| 本地JVM测试 | **507项，0失败、0错误、0跳过** |
| 主程序与正式构建 | debug／release编译和assembleRelease通过，原签名保护通过 |
| Android Lint | **0错误、6警告、2提示**；既有图标、资源目录、kapt等事项未扩展修改 |
| Android测试 | **161项测试源码编译通过，未在设备运行** |
| 真机覆盖升级 | 未执行；当前没有连接设备／模拟器 |
| 源码保护 | 217个应用源码、测试、schema、图标与Wrapper文件哈希未变 |
| 旧文件保护 | 30个旧APK／源码ZIP哈希未变，未覆盖旧交付 |
| 图标 | 用户选定图标源文件及既有资源保留 |

执行任务：testDebugUnitTest、lintDebug、compileDebugAndroidTestKotlin、assembleRelease，离线构建成功。所用JDK21，应用Java／Kotlin目标17。证据保存在D:\Codex\Workspaces\2026-09-17\referenced-chatgpt-conversation-this-is-an\work\verification-v1.11，包括release.log、summary.json、原始JUnit与Lint报告、合并manifest、APK元数据、全部APK证书核对和交付文件校验值。
## GitHub发布文档整理

2026-11-01：补充从GitHub旧版1.9.1直接升级的完整迁移说明，合并1.10功能摘要，并恢复此前发布者和联系信息。发布源码ZIP与仓库内容一致；软件代码、资源、构建配置和APK均未修改，原交付文件保留。旧1.9.1的公开构建记录保存在[BUILD_STATUS_1.9.1.md](BUILD_STATUS_1.9.1.md)。
