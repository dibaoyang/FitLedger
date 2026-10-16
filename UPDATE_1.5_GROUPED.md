# 轻衡 1.5 补充更新：今日食品分组

按用户要求，本次 versionName 仍为 1.5，versionCode 仍为 6。用户尚未安装上一份 1.5 包，改用本次 FitLedger-v1.5-grouped-update.apk。文件名用于区分安装包内容，不改变 Android 应用版本。

## 使用

同一天，同一食品库条目且单位相同的多次摄入，首页合并为一张卡片，显示总数量、总热量和次数。点击“展开”查看各次记录，点击某条“删除此次”只删除该条；收起后恢复总量显示。

例：两次土豆泥分别 100g、200g，合计显示 300g；展开后可分别删除。删除后剩一次时显示单条删除按钮，删完后该组消失。

不同食品库条目即使名称相同，也不合并；同一食品曾从 g 改为份时分组显示，避免不同单位直接相加。总热量累加每条原始摄入快照，不使用当前食品营养重算。展开项保留当时食品名称和数量；条目按记录顺序排列，不虚构时间。

此次只修改 UI 展示，原始摄入记录仍分别保存，导出备份与历史数据不变。删除沿用已有接口，限定当天和原记录 ID。首页已摄入、剩余与实际热量差仍由原始记录计算。

## 文件

- 新增 app/src/main/java/com/fitledger/ui/IntakeGroups.kt：当日按食品 ID/单位分组与快照求和。
- 新增 app/src/main/java/com/fitledger/ui/GroupedIntakes.kt：汇总卡片、展开状态、逐条删除。
- 修改 app/src/main/java/com/fitledger/ui/Screens.kt：替换今日摄入列表。
- 新增 app/src/test/java/com/fitledger/ui/IntakeGroupsTest.kt：5 项分组与删除后汇总测试。
- 更新 README.md、BUILD_STATUS.md、本说明。

Room 仍为 v4，无新增 Migration；备份版本仍为 6。applicationId com.fitledger 不变，签名与之前 1.5 完全一致。数据库、迁移、设置、备份、Repository 和应用版本配置均与上一份 1.5 源码逐字节一致。

构建和全部 74 项测试通过，Lint 无错误。未实测手机交互与覆盖安装。安装前导出备份，直接安装本次包，不要卸载或清除应用数据。前一轮生日与热量缺口功能也包含在本包中。
