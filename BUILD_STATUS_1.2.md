# 轻衡 1.2 构建与兼容性验证

日期：2026-09-20。在原 FitLedger 项目上修改。

## 实际执行

assembleRelease testDebugUnitTest lintDebug assembleDebugAndroidTest
BUILD SUCCESSFUL in 50s
121 actionable tasks: 37 executed, 84 up-to-date

- 40 项单元测试全部通过：DomainTest 15、EnergyCalculatorsTest 15、BackupMigrationTest 10；0 失败/错误。
- 公式测试覆盖男女 BMR、历史年龄/生日/闰年生日、五种活动系数、比例 5:3:2/小数/零分量/极大有限值、固定摄入、缺口/盈余、无效值、单位换算和快照稳定性。
- 宿主机真实 SQLite 测试使用已导出旧 schema 创建数据库并插入记录，执行实际 1→2→3、2→3 SQL，验证原行/待同步设置/食品 kJ 保留、新字段 NULL、最终列与 schema 一致。
- 备份测试包含新旧格式、资料/隐私/能量快照/比例往返、旧格式默认值、损坏与非法数据拒绝。
- Android Lint：0 错误，3 个非阻断提示（返回值 Composable 命名、Kapt 可换 KSP、资源 v26 目录可合并）。未关闭错误检查。
- 16 项 Android 仪器测试编译成功，测试 APK 已生成；未在真机/模拟器执行，不能标记为通过。其中包括 Room MigrationTestHelper、立即生效保持摄入/过去日期不变、资料保存与隐私不改快照、恢复事务与同步日志重试。

## 身份与签名

APK：FitLedger-v1.2-update.apk，release 构建，非 debuggable。
applicationId：com.fitledger；versionCode：3；versionName：1.2。
最低 Android API 26；目标 API 35。

新包通过 apksigner 校验，与上一版证书 SHA-256 一致：
45bbe006cb3504fbb0e422289f4c04ab433f4e226d9985101968f5312e7b7af0

APK SHA-256：
3c1a8679b5bacb9dea3ae148c937e004dc2b14829461c16784e8fa677337c448

沿用个人项目最初的签名密钥（证书名称 Android Debug），没有更换签名体系。私钥保存在工程外，不包含在源码包。构建脚本检查密钥与原指纹，缺失时不会生成新密钥替代。

## 数据兼容

Room v2→v3，MIGRATION_2_3 添加七个可空能量快照字段；旧值均留空，不反填历史。保留 MIGRATION_1_2。旧 schemas 与 v1.1 源码包一致：

1.json SHA-256：07c60a5bd97b1c5df54433e345db35beffb1b8b42bdf86364697f2eb383c6647
2.json SHA-256：08b50d9ca3ce8fcd42e3cd2d39db5d2b1bb205bcffd2a525da85a5f800902719

没有数据库破坏性迁移，没有启动删除数据或清空 DataStore 的逻辑。备份 v3 兼容 v1/v2；立即生效更新今日能量/目标，身体测量数值、摄入与过去日期不变。数据库及设置同步有事务和持久日志。

## 尚未实机验证

未验证手机实际 1.0/1.1→1.2 覆盖安装、隐私开关交互、通知调度、系统备份文件选择器和后台杀进程恢复。请在升级前导出备份，然后直接覆盖安装；不要卸载或清除存储。安装后核对历史记录。

工程同级 verification-v1.2/ 保存本轮构建日志、JUnit XML、Lint、签名、包身份和旧 schema 摘要。详细文件清单和用法见 UPDATE_1.2.md。旧版构建记录保留于 BUILD_STATUS_1.0.md、BUILD_STATUS_1.1.md。
