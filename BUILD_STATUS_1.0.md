# 构建与验证记录

验证日期：2026-09-17。

## 最终结果

最终执行了干净构建：

```text
clean assembleDebug testDebugUnitTest lintDebug assembleDebugAndroidTest
BUILD SUCCESSFUL in 1m 27s
85 actionable tasks: 85 executed
```

- Android 调试 APK：构建成功。
- 最终 APK 签名校验通过（v2 签名）。SHA-256：`ed546cd5df73e646164761fdfbd8c536f0b0d0a7c26e25c062707325be1a5eba`。
- 领域单元测试：12 项，0 失败，0 错误，0 跳过。
- Android Lint：0 错误，2 个非阻断提示（Kapt 可迁移到 KSP、空的旧资源限定目录可合并）。未禁用 Lint，也没有添加错误基线。
- Room 数据库仪器测试：6 项测试源码已编译，测试 APK 已生成；**尚未在设备上执行**。
- Room schema：已导出 `app/schemas/com.fitledger.data.LedgerDatabase/1.json`。

使用 JDK 17.0.18、Gradle 8.9、AGP 8.7.3、SDK Platform 35、Build Tools 34.0.0。Gradle 分发包已对照官方 SHA-256 校验。

## 尚未验证

没有完成真机/模拟器上的启动与交互验收。尝试列举设备时，ADB 因默认用户目录不可写而未能初始化；因此不将任何数据库仪器测试或 UI 测试标为已通过。通知授权、厂商后台省电行为、前后台跨天和不同屏幕显示仍需要设备验收。

建议设备验收顺序：首次启动输入三项数据 → 修改计划并保存 → 保存当天身体数据 → 添加按克及按份食品 → 分别记录摄入 → 锁定食品并采用推荐 → 重启确认记录保留 → 修改食品营养确认历史不变 → 多选趋势指标 → 开启提醒并检查系统权限。

## 已知使用边界

- 本版是个人使用的调试构建，未制作发行签名或上架包。
- 今日身体与目标快照保存后不可编辑；更改计划仅影响之后生成的目标。
- 推荐是有离散单位和数量上限的近似搜索，不保证最优或必然匹配。
- 所有记录仅保存在本机，尚无导出/恢复；卸载会删除数据。
- 提醒采用 WorkManager，不能保证准点。
- 首次默认营养目标是示例，需按个人需求调整。

工程同级的 `verification/` 保存最终构建日志、单元测试 XML 和 Lint 报告，便于复核。
