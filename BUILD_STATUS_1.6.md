# 轻衡 1.6 发布构建

日期：2026-10-13。用户已明确授权打包其余已完成功能，拍照识别未加入。全部源码、交付和验证文件位于 D 盘既有工程。

## 产物

- APK：FitLedger-v1.6-update.apk，release、非 debuggable，7,460,376 字节。
- versionName：1.6；versionCode：7（上一版为 1.5 / 6）。
- applicationId：com.fitledger，保持一致；最低 API 26，目标 API 35。
- APK SHA-256：fce7212b876b41f33e1f78bd97dda62f2a7f37ecae3351e6dea77bf75636501d。
- 源码归档：FitLedger-v1.6-source.zip；不含签名私钥、用户备份、构建缓存和安装包。

## 构建与检查

执行 assembleRelease testDebugUnitTest lintDebug assembleDebugAndroidTest --offline，使用 Kotlin in-process 编译策略。

BUILD SUCCESSFUL in 2m 4s
121 actionable tasks: 53 executed, 68 up-to-date

86 项主机测试全部通过，0 失败/错误。新增 12 项测试覆盖 kg／斤精度、标准化后的瘦体重/BMR/目标一致性、仅 g 食品使用快捷量、旧备份默认值、新偏好往返，以及真实 DataStore 文件关闭后重新打开和恢复保留未知键。既有食品分组、生日、缺口、推荐、运动、备份与 SQLite 迁移检查全部通过。

Android Lint：0 错误、3 个原有非阻断提示。19 项设备测试已编译并生成测试 APK，但未在真机/模拟器上执行。实际界面与手机覆盖安装未实测。

## 升级兼容性

apksigner 验证新包以及原 1.0、上一份 1.5 grouped APK 均通过，签名证书 SHA-256 相同：
45bbe006cb3504fbb0e422289f4c04ab433f4e226d9985101968f5312e7b7af0

新旧 APK 的 applicationId 均为 com.fitledger。本轮沿用工程外原签名私钥，未生成替代私钥。签名对比针对此前交付文件，未读取手机当前安装证书。若当前手机使用这些交付版本，可按同包名同签名升级；若系统提示签名冲突，保留旧 App 并反馈，不卸载处理。

Room 仍为 v4，无新增 Migration，原 1→2→3→4 链保持。数据库名称 ledger.db、DataStore 名称 settings 均不变。与上一版源码归档逐字节核对，Database.kt、Repository.kt、Migrations.kt、Backup.kt 及四份 Room schema 完全一致；构建配置除版本号外未变。

新增的 weight_input_unit 和 weight_food_shortcut 仅写入 DataStore，并纳入 PreferencesCodec/恢复。旧偏好默认 KG / true，原键不清空。备份格式保持 v6，兼容原有备份。没有破坏性数据库迁移、启动清空或历史重算。

## 安装

先在旧 App 导出备份，再打开 FitLedger-v1.6-update.apk 选择更新，直接覆盖现有 App。不卸载旧 App，不清除存储。更新后检查历史记录、食品库、计划和设置。详细功能与步骤见 UPDATE_1.6.md。

证据保存在工程同级 verification-v1.6/：build.log、JUnit XML、Lint、包元信息、签名结果、数据库兼容性对比和 SHA-256。只读发布复核通过，无已发现的发布阻断缺陷。

上次正式交付的记录保留在 BUILD_STATUS_1.5-grouped.md。PENDING_CHANGES.md 是本轮打包前的历史检查记录，已注明授权发布状态。
